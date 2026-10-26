package com.example.ble

import com.example.model.ClusterAlertType
import com.example.model.IqubeBleSpec
import com.example.model.NavigationTurnIcon
import com.example.model.QueuedWriteCommand
import com.example.model.WriteCommandStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated BLE Transmission Controller for characteristic `0x5352` (`Value Handle 0x0022`).
 *
 * Enforces strict ECU / Cluster safety rules:
 * 1. Frame Validation Guard:
 *    Every outgoing payload must be exactly 20 bytes (`IqubeBleSpec.STANDARD_FRAME_SIZE`) and
 *    use either a verified `0x5A ... 0xFF` control header (`5A F1 00 ... FF`) or a verified
 *    SmartXonnect ASCII command header (`[RU`, `[JD`, `[\W`, `[]C`).
 *    Arbitrary unverified byte injection is rejected before reaching GATT.
 * 2. Serialized FIFO Write Queue with 500ms ACK Flow Control:
 *    Never issues concurrent GATT writes on `0x0022`. Each `ATT Write Request (0x12)` waits
 *    for `ATT Write Response (0x13)` (`onCharacteristicWrite`) or times out after `500 ms`
 *    (matching Packet `#1676` supervision timeout detection).
 */
class IqubeCommandDispatcher(
    private val scope: CoroutineScope,
    private val performGattWrite: suspend (ByteArray) -> Boolean,
    private val onWriteAckTimeout: () -> Unit
) {
    private val commandIdGenerator = AtomicLong(1L)
    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<QueuedWriteCommand>()

    private var activeCommandId: Long? = null
    private var activeCommandSentAtMs: Long = 0L
    private var ackTimeoutJob: Job? = null

    private val _commandHistory = MutableStateFlow<List<QueuedWriteCommand>>(emptyList())
    val commandHistory: StateFlow<List<QueuedWriteCommand>> = _commandHistory.asStateFlow()

    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    /**
     * Validates that a 20-byte outgoing command frame strictly conforms to the reverse-engineered
     * TVS iQube SmartXonnect write protocol before transmission to Handle 0x0022 / 0x5352.
     */
    fun validateOutgoingFrame(payload: ByteArray): Result<ByteArray> {
        if (payload.size != IqubeBleSpec.STANDARD_FRAME_SIZE) {
            return Result.failure(
                IllegalArgumentException("Invalid payload length (${payload.size}B). Must be exactly 20 bytes.")
            )
        }

        val b0 = payload[0]
        val b1 = payload[1]
        val b2 = payload[2]

        // Case A: 0x5A binary control frame (must have 0x5A header and 0xFF footer at index 19)
        if (b0 == IqubeBleSpec.HEADER_TELEMETRY) {
            if (payload[19] != IqubeBleSpec.FOOTER_DELIMITER) {
                return Result.failure(
                    IllegalArgumentException("0x5A command frame must terminate with 0xFF at Byte 19.")
                )
            }
            return Result.success(payload.copyOf())
        }

        // Case B: 0x5B ('[') SmartXonnect 3-byte ASCII-prefixed frame ([RU, [JD, [\W, []C)
        if (b0 == IqubeBleSpec.HEADER_ASCII_CMD) {
            val isHandshake = b1 == 0x52.toByte() && b2 == 0x55.toByte() // [RU
            val isHeartbeat = b1 == 0x4A.toByte() && b2 == 0x44.toByte() // [JD
            val isNavFeeder = b1 == 0x5C.toByte() && b2 == 0x57.toByte() // [\W
            val isCallerAlert = b1 == 0x5D.toByte() && b2 == 0x43.toByte() // []C

            if (isHandshake || isHeartbeat || isNavFeeder || isCallerAlert) {
                return Result.success(payload.copyOf())
            }
            return Result.failure(
                IllegalArgumentException("Unrecognized 0x5B ASCII prefix. Allowed: [RU, [JD, [\\W, []C.")
            )
        }

        return Result.failure(
            IllegalArgumentException("Frame rejected: Byte 0 must be 0x5A ('Z') or 0x5B ('[').")
        )
    }

    /**
     * 1. Initial Session Handshake (`5B 52 55` / `[RU`) sent once at +1.45s after GATT setup.
     */
    fun buildInitialHandshakeFrame(riderAlias: String = "IQUBE_OSS"): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = 0x52 // 'R'
        frame[2] = 0x55 // 'U'
        frame[3] = 0x01 // Protocol version
        val ascii = riderAlias.toByteArray(StandardCharsets.US_ASCII)
        val len = minOf(ascii.size, 15)
        System.arraycopy(ascii, 0, frame, 4, len)
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    /**
     * 2. Trip Cycle / Trip Reset / Dashboard Lamp Command (`5A F1 00 <subCmd> ... FF`).
     *    - `subCommand = 0x01`: Cycle Trip A / Trip B
     *    - `subCommand = 0x02`: Reset Active Trip Meter
     *    - `subCommand = 0x03`: Toggle Dashboard Cluster Lamp
     */
    fun buildTripControlFrame(subCommand: Byte): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = 0xF1.toByte()                 // Trip / Cluster Command Opcode
        frame[2] = 0x00                          // Reserved
        frame[3] = subCommand                    // 0x01 = Cycle, 0x02 = Reset, 0x03 = Lamp
        frame[4] = 0x01                          // Payload length
        // Compute simple XOR checksum in byte 18
        var xor: Int = 0
        for (i in 0..17) {
            xor = xor xor (frame[i].toInt() and 0xFF)
        }
        frame[18] = xor.toByte()
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * 3. Periodic Dashboard / Rider Time Sync Heartbeat (`5B 4A 44` / `[JD`, every ~1.8s).
     */
    fun buildHeartbeatSyncFrame(): ByteArray {
        val cal = Calendar.getInstance()
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = 0x4A // 'J'
        frame[2] = 0x44 // 'D'
        frame[3] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        frame[4] = cal.get(Calendar.MINUTE).toByte()
        frame[5] = cal.get(Calendar.SECOND).toByte()
        frame[6] = 0x01 // Rider telemetry link active flag
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    /**
     * 4. Turn-by-Turn Navigation Feeder (`5B 5C 57` / `[\W` or `5A ... FF` framing).
     */
    fun buildNavigationFrame(
        turnIcon: NavigationTurnIcon,
        distanceMeters: Int,
        roadName: String,
        use5AEnvelope: Boolean = false
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        val clampedDist = distanceMeters.coerceIn(0, 65535)
        if (use5AEnvelope) {
            frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
            frame[1] = 0x57                          // Nav opcode
            frame[2] = turnIcon.id
            frame[3] = (clampedDist and 0xFF).toByte()
            frame[4] = ((clampedDist shr 8) and 0xFF).toByte()
            val ascii = roadName.trim().toByteArray(StandardCharsets.US_ASCII)
            val copyLen = minOf(ascii.size, 14)
            System.arraycopy(ascii, 0, frame, 5, copyLen)
            frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        } else {
            frame[0] = 0x5B // '['
            frame[1] = 0x5C // '\'
            frame[2] = 0x57 // 'W'
            frame[3] = turnIcon.id
            frame[4] = (clampedDist and 0xFF).toByte()
            frame[5] = ((clampedDist shr 8) and 0xFF).toByte()
            val ascii = roadName.trim().toByteArray(StandardCharsets.US_ASCII)
            val copyLen = minOf(ascii.size, 13)
            System.arraycopy(ascii, 0, frame, 6, copyLen)
            frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        }
        return frame
    }

    /**
     * 5. Caller ID / SMS / Alert Cluster Banner (`5B 5D 43` / `[]C`).
     */
    fun buildCallerAlertFrame(alertType: ClusterAlertType, callerOrMessageText: String): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = 0x5D // ']'
        frame[2] = 0x43 // 'C'
        frame[3] = alertType.id
        val ascii = callerOrMessageText.trim().toByteArray(StandardCharsets.US_ASCII)
        val copyLen = minOf(ascii.size, 15)
        System.arraycopy(ascii, 0, frame, 4, copyLen)
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    /**
     * Validates and enqueues a command into the serialized FIFO Write Queue.
     */
    fun enqueueCommand(label: String, rawPayload: ByteArray): Result<QueuedWriteCommand> {
        val now = System.currentTimeMillis()
        val id = commandIdGenerator.getAndIncrement()
        val validation = validateOutgoingFrame(rawPayload)

        if (validation.isFailure) {
            val rejected = QueuedWriteCommand(
                id = id,
                label = label,
                payload = rawPayload.copyOf(),
                enqueuedAtMs = now,
                status = WriteCommandStatus.REJECTED_FRAMING,
                errorDetail = validation.exceptionOrNull()?.message
            )
            recordCommandUpdate(rejected)
            return Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Invalid frame"))
        }

        val command = QueuedWriteCommand(
            id = id,
            label = label,
            payload = validation.getOrThrow(),
            enqueuedAtMs = now,
            status = WriteCommandStatus.QUEUED
        )

        scope.launch {
            queueMutex.withLock {
                pendingQueue.addLast(command)
                _queueDepth.value = pendingQueue.size
                recordCommandUpdate(command)
            }
            processNextInQueue()
        }

        return Result.success(command)
    }

    private fun processNextInQueue() {
        scope.launch {
            val nextCmd: QueuedWriteCommand? = queueMutex.withLock {
                if (activeCommandId != null || pendingQueue.isEmpty()) {
                    return@withLock null
                }
                val cmd = pendingQueue.removeFirst()
                _queueDepth.value = pendingQueue.size
                activeCommandId = cmd.id
                activeCommandSentAtMs = System.currentTimeMillis()
                cmd.copy(status = WriteCommandStatus.AWAITING_ACK)
            }

            if (nextCmd != null) {
                recordCommandUpdate(nextCmd)

                // Start 500ms ATT Write Response (0x13) watchdog timer
                ackTimeoutJob?.cancel()
                ackTimeoutJob = scope.launch {
                    delay(IqubeBleSpec.WRITE_ACK_TIMEOUT_MS)
                    handleWriteTimeout(nextCmd.id)
                }

                val writeInitiated = performGattWrite(nextCmd.payload)
                if (!writeInitiated) {
                    ackTimeoutJob?.cancel()
                    queueMutex.withLock {
                        if (activeCommandId == nextCmd.id) {
                            activeCommandId = null
                        }
                    }
                    recordCommandUpdate(
                        nextCmd.copy(
                            status = WriteCommandStatus.DISCONNECTED_ERROR,
                            errorDetail = "GATT write characteristic unavailable"
                        )
                    )
                    processNextInQueue()
                }
            }
        }
    }

    /**
     * Called by `BluetoothGattCallback.onCharacteristicWrite` (ATT Write Response 0x13)
     * or by the Replay/Loopback ACK handler.
     */
    fun onWriteResponseReceived(gattStatus: Int = 0) {
        scope.launch {
            val completedId: Long?
            val latencyMs: Long
            queueMutex.withLock {
                completedId = activeCommandId
                latencyMs = (System.currentTimeMillis() - activeCommandSentAtMs).coerceAtLeast(1L)
                activeCommandId = null
                ackTimeoutJob?.cancel()
            }

            if (completedId != null) {
                _commandHistory.update { list ->
                    list.map { item ->
                        if (item.id == completedId) {
                            if (gattStatus == 0) {
                                item.copy(status = WriteCommandStatus.ACK_RECEIVED, ackLatencyMs = latencyMs)
                            } else {
                                item.copy(
                                    status = WriteCommandStatus.DISCONNECTED_ERROR,
                                    ackLatencyMs = latencyMs,
                                    errorDetail = "GATT status 0x${Integer.toHexString(gattStatus)}"
                                )
                            }
                        } else item
                    }
                }
            }
            processNextInQueue()
        }
    }

    private suspend fun handleWriteTimeout(timedOutId: Long) {
        var didTimeout = false
        queueMutex.withLock {
            if (activeCommandId == timedOutId) {
                activeCommandId = null
                didTimeout = true
            }
        }
        if (didTimeout) {
            _commandHistory.update { list ->
                list.map { item ->
                    if (item.id == timedOutId) {
                        item.copy(
                            status = WriteCommandStatus.TIMEOUT_500MS,
                            ackLatencyMs = IqubeBleSpec.WRITE_ACK_TIMEOUT_MS,
                            errorDetail = "ATT Write Response (0x13) timed out >500ms (Packet #1676 condition)"
                        )
                    } else item
                }
            }
            onWriteAckTimeout()
            processNextInQueue()
        }
    }

    fun clearPendingQueueOnDisconnect() {
        scope.launch {
            queueMutex.withLock {
                ackTimeoutJob?.cancel()
                activeCommandId = null
                pendingQueue.clear()
                _queueDepth.value = 0
            }
        }
    }

    private fun recordCommandUpdate(cmd: QueuedWriteCommand) {
        _commandHistory.update { current ->
            val existingIdx = current.indexOfFirst { it.id == cmd.id }
            val updated = if (existingIdx >= 0) {
                current.toMutableList().apply { set(existingIdx, cmd) }
            } else {
                listOf(cmd) + current
            }
            updated.take(40)
        }
    }
}
