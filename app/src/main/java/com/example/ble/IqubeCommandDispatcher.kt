package com.example.ble

import com.example.model.IqubeBleSpec
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
 * Enforces the 100% verified outgoing 20-byte frame formats (every frame ends with `packet[19] = 0xFF`):
 * 1. Initial Handshake (`[RU`): `[0x5B, 0x52, 0x55, 0x01, ...ascii("IQUBE_OSS"), ...0x00, 0xFF]`
 * 2. Periodic Clock Sync Heartbeat (`[JD`, every 1.8s): `[0x5B, 0x4A, 0x44, hours, minutes, seconds, 0x01, 0, ..., 0, 0xFF]`
 * 3. Live GPS Location Dump (`[\` + 17 ASCII chars + `0xFF`): `[0x5B, 0x5C, ...ascii(localityName.slice(0, 17)), ...0x00, 0xFF]`
 * 4. 3 Verified Trip Control Buttons (`Switch to Trip A`, `Switch to Trip B`, `Reset Active Trip (A/B)`):
 *    `[0x5A, 0xF1, 0x00, subCmd, 0, ..., 0, 0xFF]`
 * 5. Incoming Call Cluster Alert (`[\` + `"CALL:" + firstName` up to 17 ASCII chars + `0xFF` and `[]C` frame).
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
     * Validates that a 20-byte outgoing command frame strictly conforms to the verified
     * TVS iQube SmartXonnect write protocol (including mandatory `Byte 19 = 0xFF` trailer)
     * before transmission to Handle 0x0022 / 0x5352.
     */
    fun validateOutgoingFrame(payload: ByteArray): Result<ByteArray> {
        if (payload.size != IqubeBleSpec.STANDARD_FRAME_SIZE) {
            return Result.failure(
                IllegalArgumentException("Invalid payload length (${payload.size}B). Must be exactly 20 bytes.")
            )
        }

        // Mandatory Frame Trailer Rule: Every 20-byte packet must end with Byte 19 = 0xFF
        if (payload[19] != IqubeBleSpec.FOOTER_DELIMITER) {
            return Result.failure(
                IllegalArgumentException("Frame rejected: Byte 19 must be 0xFF trailer.")
            )
        }

        val b0 = payload[0]
        val b1 = payload[1]
        val b2 = payload[2]

        // Case A: 0x5A binary control frame ([0x5A, 0xF1, 0x00, subCmd, ..., 0xFF])
        if (b0 == IqubeBleSpec.HEADER_TELEMETRY) {
            return Result.success(payload.copyOf())
        }

        // Case B: 0x5B ('[') SmartXonnect frame:
        // - [RU (0x5B, 0x52, 0x55): Initial Handshake
        // - [JD (0x5B, 0x4A, 0x44): Periodic Clock Sync Heartbeat
        // - [\  (0x5B, 0x5C, ...17B ASCII, 0xFF): Cluster Location / Call Display Packet
        // - []C (0x5B, 0x5D, 0x43, ...15B ASCII, 0xFF): Secondary Call Notification Frame
        if (b0 == IqubeBleSpec.HEADER_ASCII_CMD) {
            val isHandshake = b1 == 0x52.toByte() && b2 == 0x55.toByte() // [RU
            val isHeartbeat = b1 == 0x4A.toByte() && b2 == 0x44.toByte() // [JD
            val isLocationOrCall = b1 == 0x5C.toByte()                   // [\ + 17B ASCII + 0xFF
            val isCallerFrame = b1 == 0x5D.toByte() && b2 == 0x43.toByte() // []C

            if (isHandshake || isHeartbeat || isLocationOrCall || isCallerFrame) {
                return Result.success(payload.copyOf())
            }
            return Result.failure(
                IllegalArgumentException("Unrecognized 0x5B header. Allowed: [RU, [JD, [\\ (5B 5C), or []C.")
            )
        }

        return Result.failure(
            IllegalArgumentException("Frame rejected: Byte 0 must be 0x5A ('Z') or 0x5B ('[').")
        )
    }

    /**
     * 1. Initial Session Handshake (`[RU`):
     *    `[0x5B, 0x52, 0x55, 0x01, ...ascii("IQUBE_OSS"), ...0x00, 0xFF]`
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
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * 2. Periodic Clock Sync Heartbeat (`[JD`, every 1.8s):
     *    `[0x5B, 0x4A, 0x44, hours, minutes, seconds, 0x01, 0, ..., 0, 0xFF]`
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
        frame[6] = 0x01
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF
        return frame
    }

    /**
     * 3. Live GPS Location Dump (`[\` + up to 17 ASCII chars in `Bytes 2..18` + `Byte 19 = 0xFF`):
     *    `[0x5B, 0x5C, ...ascii(localityName.slice(0, 17)), ...0x00, 0xFF]`
     */
    fun buildClusterLocationPacket(locationName: String): ByteArray {
        val packet = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE) // zero-filled (0x00 padded)
        packet[0] = 0x5B // '['
        packet[1] = 0x5C // '\' Location Frame ID
        val cleanAscii = locationName
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(17)
        val asciiBytes = cleanAscii.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(asciiBytes, 0, packet, 2, minOf(asciiBytes.size, 17))
        packet[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return packet
    }

    /**
     * 4. Three Verified Trip Control Buttons (`Switch to Trip A`, `Switch to Trip B`, `Reset Active Trip (A/B)`):
     *    `[0x5A, 0xF1, 0x00, subCmd, 0, ..., 0, 0xFF]`
     */
    fun buildTripControlFrame(subCommand: Byte): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = 0xF1.toByte()                 // Trip Control Opcode
        frame[2] = 0x00                          // Reserved
        frame[3] = subCommand                    // 0x03 = Switch Trip A, 0x01 = Switch Trip B, 0x02 = Reset Active Trip
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return frame
    }

    /**
     * 5A. Incoming Call Cluster Location Override (`0x5B 0x5C` + `"CALL:" + firstName` + `0xFF`):
     *     Extracts caller's 1st name and formats up to 17 ASCII chars into Bytes 2..18 with Byte 19 = 0xFF.
     */
    fun buildIncomingCallClusterPacket(fullCallerName: String): ByteArray {
        val firstName = fullCallerName.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.replace(Regex("[^\\x20-\\x7E]"), "")
            ?.ifBlank { "Caller" } ?: "Caller"
        return buildClusterLocationPacket("CALL:$firstName")
    }

    /**
     * 5B. Secondary Incoming Call Alert Packet (`[]C` + caller's 1st name + `0xFF`):
     *     `[0x5B, 0x5D, 0x43, 0x01, ...asciiBytes(firstName.slice(0, 15)), ...0x00, 0xFF]`
     */
    fun buildCallerAlertFrame0x5D(firstName: String): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = 0x5B // '['
        frame[1] = 0x5D // ']'
        frame[2] = 0x43 // 'C'
        frame[3] = 0x01 // Incoming call alert flag
        val cleanAscii = firstName
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .trim()
            .take(15)
        val asciiBytes = cleanAscii.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(asciiBytes, 0, frame, 4, minOf(asciiBytes.size, 15))
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER // 0xFF mandatory trailer
        return frame
    }

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
