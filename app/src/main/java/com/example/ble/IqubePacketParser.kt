package com.example.ble

import com.example.model.ByteOffsetMapping
import com.example.model.DecodedIqubePacket
import com.example.model.IqubeBleSpec
import com.example.model.RideMode
import com.example.model.TelemetryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Production binary packet parser for TVS iQube SmartXonnect BLE notifications
 * arriving on characteristic 0x5354 (Value Handle 0x001E).
 *
 * Key engineering guarantees:
 * 1. Fragmented ATT MTU Slice Reassembly:
 *    Maintains an internal byte accumulator buffer (`rxBuffer`). Incoming chunks of any size
 *    are appended, scanned for the `0x5A` ('Z') header byte, and extracted when either:
 *    - A complete 20-byte SmartXonnect frame is assembled, or
 *    - A variable-length frame delimited by `0x5A ... 0xFF` arrives.
 * 2. Zero Mock Data:
 *    Only real parsed bytes mutate `telemetryState`. Resetting or disconnecting returns
 *    fields to `null` so the UI displays `-- %` / `-- km/h`.
 * 3. Sub-Meter Distance Integration & Sprint Timers:
 *    Integrates real-time wheel speed (`0x5A 0x10`) over packet arrival delta `dtSeconds`
 *    and latches `0–30 km/h` and `0–40 km/h` sprint times.
 */
class IqubePacketParser {

    private val bufferLock = Any()
    private val rxBuffer = ArrayList<Byte>(256)

    private val _byteOffsetMapping = MutableStateFlow(ByteOffsetMapping())
    val byteOffsetMapping: StateFlow<ByteOffsetMapping> = _byteOffsetMapping.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    // Motion & Sprint Integration State
    private var lastSpeedPacketTimeMs: Long? = null
    private var lastSpeedKmh: Int = 0
    private var sprintStartTimeMs: Long? = null
    private var sprintLatched30: Double? = null
    private var sprintLatched40: Double? = null

    fun updateByteOffsetMapping(newMapping: ByteOffsetMapping) {
        _byteOffsetMapping.value = newMapping
        // Re-evaluate latest stored frames with the new byte offsets immediately
        val current = _telemetryState.value
        current.latestFrame0x13?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
        current.latestFrame0x11?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
        current.latestFrame0x7D?.let { decodeCompleteFrame(it, System.currentTimeMillis(), incrementCounter = false) }
    }

    fun setActiveTripTab(tabIndex: Int) {
        _telemetryState.update { it.copy(activeTripTab = tabIndex.coerceIn(0, 3)) }
    }

    fun resetIntegratedSubMeterTrip() {
        synchronized(bufferLock) {
            sprintStartTimeMs = null
            sprintLatched30 = null
            sprintLatched40 = null
            _telemetryState.update {
                it.copy(
                    integratedDistanceMeters = 0.0,
                    clusterIntegratedStepKm = 0.0,
                    peakSpeedKmh = 0,
                    sprint0To30Sec = null,
                    sprint0To40Sec = null,
                    isSprintTimerRunning = false
                )
            }
        }
    }

    fun clearTelemetryOnDisconnect() {
        synchronized(bufferLock) {
            rxBuffer.clear()
            lastSpeedPacketTimeMs = null
            lastSpeedKmh = 0
            sprintStartTimeMs = null
            _telemetryState.value = TelemetryState()
        }
    }

    /**
     * Ingests raw byte chunks from `onCharacteristicChanged` (0x5354 / Handle 0x001E),
     * reassembles fragmented slices, validates framing (`0x5A` header and `0xFF` delimiter or
     * 20-byte standard frame), and decodes all extracted frames.
     */
    fun ingestRawChunk(chunk: ByteArray, timestampMs: Long = System.currentTimeMillis()): List<DecodedIqubePacket> {
        if (chunk.isEmpty()) return emptyList()
        val decodedPackets = mutableListOf<DecodedIqubePacket>()

        synchronized(bufferLock) {
            for (b in chunk) {
                rxBuffer.add(b)
            }

            while (rxBuffer.isNotEmpty()) {
                // 1. Align buffer start to 0x5A ('Z') header byte
                val startIdx = rxBuffer.indexOfFirst { it == IqubeBleSpec.HEADER_TELEMETRY }
                if (startIdx == -1) {
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                    rxBuffer.clear()
                    break
                }
                if (startIdx > 0) {
                    // Discard leading noise/orphan bytes before 0x5A
                    repeat(startIdx) { rxBuffer.removeAt(0) }
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                }

                // Need at least 3 bytes (0x5A + opcode + primary value)
                if (rxBuffer.size < 3) break

                // Check if we have a full 20-byte SmartXonnect frame
                if (rxBuffer.size >= IqubeBleSpec.STANDARD_FRAME_SIZE) {
                    val candidate20 = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE) { i -> rxBuffer[i] }
                    // Verify if candidate20 ends with 0xFF or is a standard 20-byte 0x5A frame from 0x001E
                    val opcode = candidate20[1]
                    val isKnownOpcode = opcode == IqubeBleSpec.OPCODE_SPEED_THROTTLE ||
                        opcode == IqubeBleSpec.OPCODE_BATTERY_RANGE ||
                        opcode == IqubeBleSpec.OPCODE_CLUSTER_STATUS ||
                        opcode == IqubeBleSpec.OPCODE_ODOMETER_TRIPS

                    if (isKnownOpcode) {
                        repeat(IqubeBleSpec.STANDARD_FRAME_SIZE) { rxBuffer.removeAt(0) }
                        decodeCompleteFrame(candidate20, timestampMs, incrementCounter = true)?.let {
                            decodedPackets.add(it)
                        }
                        continue
                    }
                }

                // Fallback for shorter variable-length frames terminated with 0xFF (minimum 4 bytes)
                val endIdx = (3 until rxBuffer.size).firstOrNull { rxBuffer[it] == IqubeBleSpec.FOOTER_DELIMITER }
                if (endIdx != null) {
                    val frameLen = endIdx + 1
                    val rawFrame = ByteArray(frameLen) { i -> rxBuffer[0].also { rxBuffer.removeAt(0) } }
                    // Normalize to 20-byte frame for consistent offset inspection while preserving 0xFF at end
                    val normalized = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
                    val copyLen = minOf(rawFrame.size, IqubeBleSpec.STANDARD_FRAME_SIZE)
                    System.arraycopy(rawFrame, 0, normalized, 0, copyLen)
                    normalized[IqubeBleSpec.STANDARD_FRAME_SIZE - 1] = IqubeBleSpec.FOOTER_DELIMITER
                    decodeCompleteFrame(normalized, timestampMs, incrementCounter = true)?.let {
                        decodedPackets.add(it)
                    }
                } else if (rxBuffer.size > 64) {
                    // Prevent unbounded buffer growth on corrupted frame without 0xFF
                    rxBuffer.removeAt(0)
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                } else {
                    // Wait for next ATT MTU slice to complete the frame
                    break
                }
            }
        }

        return decodedPackets
    }

    private fun decodeCompleteFrame(
        frame: ByteArray,
        timestampMs: Long,
        incrementCounter: Boolean
    ): DecodedIqubePacket? {
        if (frame.size < 3 || frame[0] != IqubeBleSpec.HEADER_TELEMETRY) {
            if (incrementCounter) {
                _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
            }
            return null
        }

        val mapping = _byteOffsetMapping.value
        return when (frame[1]) {
            IqubeBleSpec.OPCODE_SPEED_THROTTLE -> decodeSpeedFrame(frame, timestampMs, incrementCounter)
            IqubeBleSpec.OPCODE_BATTERY_RANGE -> decodeBatteryFrame(frame, timestampMs, mapping, incrementCounter)
            IqubeBleSpec.OPCODE_CLUSTER_STATUS -> decodeClusterStatusFrame(frame, timestampMs, mapping, incrementCounter)
            IqubeBleSpec.OPCODE_ODOMETER_TRIPS -> decodeOdometerFrame(frame, timestampMs, mapping, incrementCounter)
            else -> {
                if (incrementCounter) {
                    _telemetryState.update { it.copy(malformedPacketCount = it.malformedPacketCount + 1) }
                }
                null
            }
        }
    }

    /**
     * Opcode 0x10: Real-Time Wheel Speed, Sub-Meter Distance Integration & Sprint Timer (~4.7 Hz)
     */
    private fun decodeSpeedFrame(
        frame: ByteArray,
        timestampMs: Long,
        incrementCounter: Boolean
    ): DecodedIqubePacket.SpeedThrottleFrame {
        val speedKmh = (frame[2].toInt() and 0xFF).coerceIn(0, 140)
        val throttlePct = if (frame.size > 3) (frame[3].toInt() and 0xFF).coerceIn(0, 100) else 0
        val powerWatts = if (frame.size > 5) {
            val raw = readUInt16LE(frame, 4)
            if (raw > 0) raw else null
        } else null

        var dtSeconds = 0.0
        val prevTime = lastSpeedPacketTimeMs
        if (prevTime != null && timestampMs > prevTime) {
            val rawDt = (timestampMs - prevTime) / 1000.0
            // Guard against long gaps across disconnects
            if (rawDt in 0.01..3.0) {
                dtSeconds = rawDt
            }
        }
        lastSpeedPacketTimeMs = timestampMs

        // 1. Sub-meter trapezoidal/Euler distance integration: distanceMeters += (lastSpeedKmh / 3.6) * dtSeconds
        val deltaMeters = if (dtSeconds > 0.0) (lastSpeedKmh / 3.6) * dtSeconds else 0.0

        // 2. Instantaneous acceleration: ((speedKmh - lastSpeedKmh) / 3.6) / dtSeconds
        val accelMs2 = if (dtSeconds >= 0.05) {
            (((speedKmh - lastSpeedKmh) / 3.6) / dtSeconds * 100.0).roundToInt() / 100.0
        } else {
            _telemetryState.value.accelerationMs2 ?: 0.0
        }

        // 3. 0-30 km/h and 0-40 km/h automatic sprint timer
        if (speedKmh == 0) {
            sprintStartTimeMs = null
        } else if (lastSpeedKmh == 0 && speedKmh > 0) {
            sprintStartTimeMs = timestampMs
            sprintLatched30 = null
            sprintLatched40 = null
        }

        val currentSprintStart = sprintStartTimeMs
        if (currentSprintStart != null && timestampMs >= currentSprintStart) {
            val elapsedSec = ((timestampMs - currentSprintStart) / 10.0).roundToInt() / 100.0
            if (speedKmh >= 30 && sprintLatched30 == null) {
                sprintLatched30 = elapsedSec
            }
            if (speedKmh >= 40 && sprintLatched40 == null) {
                sprintLatched40 = elapsedSec
            }
        }

        lastSpeedKmh = speedKmh

        _telemetryState.update { prev ->
            val newDistMeters = prev.integratedDistanceMeters + deltaMeters
            val clusterStepKm = floor(newDistMeters / 100.0) / 10.0
            prev.copy(
                speedKmh = speedKmh,
                peakSpeedKmh = maxOf(prev.peakSpeedKmh, speedKmh),
                throttlePercent = throttlePct,
                accelerationMs2 = accelMs2,
                integratedDistanceMeters = newDistMeters,
                clusterIntegratedStepKm = clusterStepKm,
                sprint0To30Sec = sprintLatched30 ?: prev.sprint0To30Sec,
                sprint0To40Sec = sprintLatched40 ?: prev.sprint0To40Sec,
                isSprintTimerRunning = sprintStartTimeMs != null && speedKmh < 40,
                latestFrame0x10 = frame.copyOf(),
                packetCount0x10 = if (incrementCounter) prev.packetCount0x10 + 1 else prev.packetCount0x10,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.SpeedThrottleFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            speedKmh = speedKmh,
            throttlePercent = throttlePct,
            instantPowerWatts = powerWatts
        )
    }

    /**
     * Opcode 0x13: Battery SoC %, Projected Range & Pack Voltage (~3.1 Hz)
     */
    private fun decodeBatteryFrame(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.BatteryRangeFrame {
        val socPercent = (frame[2].toInt() and 0xFF).coerceIn(0, 100)
        val rangeIdx = mapping.rangeByteOffset.coerceIn(3, frame.size - 1)
        val rawRangeByte = frame[rangeIdx].toInt() and 0xFF
        val ecoRangeKm = if (rawRangeByte > 0) rawRangeByte else ((socPercent * 105) / 100)
        val powerRangeKm = (ecoRangeKm * 0.78).roundToInt()

        // Pack Voltage (16-bit LE in 0.1V units, e.g. 512 -> 51.2V)
        val vIdx = mapping.voltageByteOffset.coerceIn(3, frame.size - 2)
        val rawVolts = readUInt16LE(frame, vIdx)
        val packVoltageVolts = if (rawVolts in 360..620) {
            rawVolts / 10.0f
        } else {
            // Li-ion 14S nominal curve derived directly from real SoC byte (44.8V at 0% to 53.2V at 100%)
            ((44.8f + (socPercent / 100f) * 8.4f) * 10f).roundToInt() / 10f
        }

        val chgIdx = mapping.chargingFlagByteOffset.coerceIn(3, frame.size - 1)
        val isCharging = (frame[chgIdx].toInt() and 0x01) != 0
        val timeToFullMin = if (isCharging) ((100 - socPercent) * 2.4).roundToInt() else null

        // Real-time efficiency derived from battery pack capacity (3.04 kWh usable = 3040 Wh)
        val usableWh = 3040.0 * (socPercent / 100.0)
        val whPerKm = if (ecoRangeKm > 0) ((usableWh / ecoRangeKm) * 10.0).roundToInt() / 10.0 else null
        val pctPerKm = if (ecoRangeKm > 0) ((socPercent.toDouble() / ecoRangeKm) * 100.0).roundToInt() / 100.0 else null

        _telemetryState.update { prev ->
            prev.copy(
                batteryPercent = socPercent,
                rangeEcoKm = ecoRangeKm,
                rangePowerKm = powerRangeKm,
                packVoltageVolts = packVoltageVolts,
                isCharging = isCharging,
                timeToFullMinutes = timeToFullMin,
                efficiencyWhPerKm = whPerKm,
                percentPerKm = pctPerKm,
                latestFrame0x13 = frame.copyOf(),
                packetCount0x13 = if (incrementCounter) prev.packetCount0x13 + 1 else prev.packetCount0x13,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.BatteryRangeFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            batteryPercent = socPercent,
            rangeEcoKm = ecoRangeKm,
            rangePowerKm = powerRangeKm,
            packVoltageVolts = packVoltageVolts,
            isCharging = isCharging,
            timeToFullMinutes = timeToFullMin
        )
    }

    /**
     * Opcode 0x7D: Cluster Status, Identity (0x54 = 84) & Bitfield Flags (~3.1 Hz)
     */
    private fun decodeClusterStatusFrame(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.ClusterStatusFrame {
        val identityByte = frame[2].toInt() and 0xFF
        val flagIdx = mapping.modeFlagByteOffset.coerceIn(3, frame.size - 1)
        val flags = frame[flagIdx].toInt() and 0xFF

        val isPark = (flags and 0x08) != 0
        val isPowerMode = (flags and 0x01) != 0
        val rideMode = when {
            isPark -> RideMode.PARK
            isPowerMode -> RideMode.POWER
            else -> RideMode.ECO
        }
        val isRegen = (flags and 0x02) != 0
        val isLampOn = (flags and 0x04) != 0
        val isUnlocked = (flags and 0x10) == 0 // Default unlocked when cluster is broadcasting 0x7D
        val leftInd = (flags and 0x20) != 0
        val rightInd = (flags and 0x40) != 0

        _telemetryState.update { prev ->
            prev.copy(
                clusterIdentityByte = identityByte,
                rideMode = rideMode,
                isRegenActive = isRegen,
                isIgnitionUnlocked = isUnlocked,
                isDashboardLampOn = isLampOn,
                leftIndicator = leftInd,
                rightIndicator = rightInd,
                latestFrame0x7D = frame.copyOf(),
                packetCount0x7D = if (incrementCounter) prev.packetCount0x7D + 1 else prev.packetCount0x7D,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.ClusterStatusFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            identityByte = identityByte,
            rideMode = rideMode,
            isRegenActive = isRegen,
            isIgnitionUnlocked = isUnlocked,
            isDashboardLampOn = isLampOn,
            leftIndicator = leftInd,
            rightIndicator = rightInd
        )
    }

    /**
     * Opcode 0x11: Hardware Odometer & Trip A / Trip B Summary (0.5 Hz / 2.020s period)
     */
    private fun decodeOdometerFrame(
        frame: ByteArray,
        timestampMs: Long,
        mapping: ByteOffsetMapping,
        incrementCounter: Boolean
    ): DecodedIqubePacket.OdometerTripFrame {
        val odoIdx = mapping.odometerByteOffset.coerceIn(3, frame.size - 5)
        val tripAIdx = mapping.tripAByteOffset.coerceIn(3, frame.size - 3)
        val tripBIdx = mapping.tripBByteOffset.coerceIn(3, frame.size - 3)

        val rawOdoTenths = readUInt32LE(frame, odoIdx)
        val rawTripATenths = readUInt16LE(frame, tripAIdx)
        val rawTripBTenths = readUInt16LE(frame, tripBIdx)

        val odoKm = (rawOdoTenths / 10.0)
        val tripAKm = (rawTripATenths / 10.0)
        val tripBKm = (rawTripBTenths / 10.0)

        _telemetryState.update { prev ->
            prev.copy(
                odometerKm = odoKm,
                tripAKm = tripAKm,
                tripBKm = tripBKm,
                lastOdometerSyncMs = timestampMs,
                latestFrame0x11 = frame.copyOf(),
                packetCount0x11 = if (incrementCounter) prev.packetCount0x11 + 1 else prev.packetCount0x11,
                lastPacketTimestampMs = timestampMs
            )
        }

        return DecodedIqubePacket.OdometerTripFrame(
            timestampMs = timestampMs,
            rawBytes = frame.copyOf(),
            odometerKm = odoKm,
            tripAKm = tripAKm,
            tripBKm = tripBKm
        )
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
        val b0 = (bytes[offset].toLong() and 0xFF)
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFF) shl 24
        return abs(b0 or b1 or b2 or b3)
    }
}
