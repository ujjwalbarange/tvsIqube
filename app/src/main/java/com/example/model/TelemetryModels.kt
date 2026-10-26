package com.example.model

import java.util.Locale

/**
 * Target BLE Hardware & UUID Constants for TVS iQube (SmartXonnect BLE Cluster)
 */
object IqubeBleSpec {
    const val TARGET_MAC_ADDRESS = "F8:55:48:72:96:DA"
    const val DEFAULT_DEVICE_ALIAS = "TVSiQUBEJI573"

    // Custom 128-bit SmartXonnect Primary Service UUID & Known Characteristics
    const val SERVICE_UUID_SMARTXONNECT = "5456534d-5647-5341-5342-454e544f5251"
    const val CHAR_UUID_TELEMETRY_NOTIFY = "00005354-0000-1000-8000-00805f9b34fb" // 0x5354 / Handle 0x001E
    const val CHAR_UUID_COMMAND_WRITE = "00005352-0000-1000-8000-00805f9b34fb"    // 0x5352 / Handle 0x0022
    const val DESCRIPTOR_UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb"       // Handle 0x001F
    const val DESCRIPTOR_UUID_CUD = "00002901-0000-1000-8000-00805f9b34fb"        // Handle 0x0023

    // Protocol Framing Bytes
    const val HEADER_TELEMETRY: Byte = 0x5A // 'Z' (90 decimal)
    const val HEADER_ASCII_CMD: Byte = 0x5B // '[' (91 decimal)
    const val FOOTER_DELIMITER: Byte = 0xFF.toByte()
    const val STANDARD_FRAME_SIZE = 20

    // Incoming Telemetry Opcodes (Scooter -> Phone on 0x5354 / Handle 0x001E)
    const val OPCODE_SPEED_THROTTLE: Byte = 0x10
    const val OPCODE_ODOMETER_TRIPS: Byte = 0x11
    const val OPCODE_BATTERY_RANGE: Byte = 0x13
    const val OPCODE_CLUSTER_STATUS: Byte = 0x7D

    // Outgoing Command Prefixes (Phone -> Scooter on 0x5352 / Handle 0x0022)
    val PREFIX_HANDSHAKE_RU = byteArrayOf(0x5B, 0x52, 0x55) // "[RU"
    val PREFIX_HEARTBEAT_JD = byteArrayOf(0x5B, 0x4A, 0x44) // "[JD"
    val PREFIX_NAV_FEEDER_W = byteArrayOf(0x5B, 0x5C, 0x57) // "[\W"
    val PREFIX_CALLER_ALERT_C = byteArrayOf(0x5B, 0x5D, 0x43) // "[]C"
    val PREFIX_TRIP_CONTROL = byteArrayOf(0x5A, 0xF1.toByte(), 0x00)

    // Link & Timing Constants
    const val REQUESTED_MTU = 517
    const val EXPECTED_NEGOTIATED_MTU = 247
    const val WRITE_ACK_TIMEOUT_MS = 500L
    const val LINK_WATCHDOG_DEGRADED_MS = 2500L
    const val SUPERVISION_TIMEOUT_MS = 6000L
    const val HEARTBEAT_INTERVAL_MS = 1800L
}

enum class BleConnectionPhase {
    DISCONNECTED,
    SCANNING,
    CONNECTING_GATT,
    DISCOVERING_SERVICES,
    ENABLING_CCCD,
    NEGOTIATING_MTU,
    SENDING_HANDSHAKE,
    STREAMING_ACTIVE,
    REPLAYING_HCI_LOG,
    RECONNECTING_BACKOFF
}

enum class RideMode(val label: String) {
    ECO("ECO MODE"),
    POWER("POWER MODE"),
    PARK("PARK / STANDBY")
}

enum class NavigationTurnIcon(val id: Byte, val label: String, val symbol: String) {
    STRAIGHT(0x01, "Continue Straight", "↑"),
    TURN_LEFT(0x02, "Turn Left", "↰"),
    TURN_RIGHT(0x03, "Turn Right", "↱"),
    SHARP_LEFT(0x04, "Sharp Left", "↙"),
    SHARP_RIGHT(0x05, "Sharp Right", "↘"),
    U_TURN(0x06, "Make U-Turn", "↶"),
    ROUNDABOUT(0x07, "Roundabout Exit", "⟳"),
    DESTINATION(0x08, "Arrive Destination", "⚑")
}

enum class ClusterAlertType(val id: Byte, val label: String) {
    INCOMING_CALL(0x01, "Incoming Call"),
    MISSED_CALL(0x02, "Missed Call"),
    SMS_MESSAGE(0x03, "SMS / Message"),
    NAVIGATION_ALERT(0x04, "Navigation Prompt"),
    SPEED_WARNING(0x05, "High Speed Warning")
}

/**
 * User-configurable Byte-Offset Mapper for Bytes 3..19 in incoming 20-byte frames.
 */
data class ByteOffsetMapping(
    val rangeByteOffset: Int = 3,          // In 0x13 frame (default Byte 3: 0x60 = 96 km)
    val voltageByteOffset: Int = 4,        // In 0x13 or 0x10 frame (16-bit LE in 0.1V, e.g. 512 = 51.2V)
    val chargingFlagByteOffset: Int = 6,   // In 0x13 frame (Bit 0 = charging)
    val odometerByteOffset: Int = 3,       // In 0x11 frame (32-bit LE in 0.1 km units)
    val tripAByteOffset: Int = 7,          // In 0x11 frame (16-bit LE in 0.1 km units)
    val tripBByteOffset: Int = 9,          // In 0x11 frame (16-bit LE in 0.1 km units)
    val modeFlagByteOffset: Int = 3        // In 0x7D frame (Bit 0 = Power mode, Bit 1 = Regen, Bit 2 = Lamp)
)

/**
 * Strongly-typed representation of a single decoded BLE packet from 0x5354 (Handle 0x001E).
 */
sealed class DecodedIqubePacket(
    open val timestampMs: Long,
    open val rawBytes: ByteArray
) {
    data class SpeedThrottleFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val speedKmh: Int,
        val throttlePercent: Int,
        val instantPowerWatts: Int?
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class BatteryRangeFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val batteryPercent: Int,
        val rangeEcoKm: Int,
        val rangePowerKm: Int,
        val packVoltageVolts: Float?,
        val isCharging: Boolean,
        val timeToFullMinutes: Int?
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class ClusterStatusFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val identityByte: Int, // 0x54 (84 decimal)
        val rideMode: RideMode,
        val isRegenActive: Boolean,
        val isIgnitionUnlocked: Boolean,
        val isDashboardLampOn: Boolean,
        val leftIndicator: Boolean,
        val rightIndicator: Boolean
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class OdometerTripFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val odometerKm: Double,
        val tripAKm: Double,
        val tripBKm: Double
    ) : DecodedIqubePacket(timestampMs, rawBytes)
}

/**
 * Complete reactive Telemetry State exposed to the Compose UI.
 * All telemetry values default to null when disconnected or before packets arrive,
 * strictly enforcing Zero Hardcoded / Mock Data in the UI.
 */
data class TelemetryState(
    // Speed & Motion (from 0x5A 0x10)
    val speedKmh: Int? = null,
    val peakSpeedKmh: Int = 0,
    val throttlePercent: Int? = null,
    val accelerationMs2: Double? = null,
    val integratedDistanceMeters: Double = 0.0,
    val clusterIntegratedStepKm: Double = 0.0,
    val sprint0To30Sec: Double? = null,
    val sprint0To40Sec: Double? = null,
    val isSprintTimerRunning: Boolean = false,

    // Battery & Range (from 0x5A 0x13)
    val batteryPercent: Int? = null,
    val rangeEcoKm: Int? = null,
    val rangePowerKm: Int? = null,
    val packVoltageVolts: Float? = null,
    val isCharging: Boolean = false,
    val timeToFullMinutes: Int? = null,
    val efficiencyWhPerKm: Double? = null,
    val percentPerKm: Double? = null,

    // Odometer & Trips (from 0x5A 0x11)
    val odometerKm: Double? = null,
    val tripAKm: Double? = null,
    val tripBKm: Double? = null,
    val activeTripTab: Int = 0, // 0 = Trip A, 1 = Trip B, 2 = ODO, 3 = Sub-Meter
    val lastOdometerSyncMs: Long? = null,

    // Cluster Status & Bitfields (from 0x5A 0x7D)
    val clusterIdentityByte: Int? = null,
    val rideMode: RideMode? = null,
    val isRegenActive: Boolean = false,
    val isIgnitionUnlocked: Boolean? = null,
    val isDashboardLampOn: Boolean = false,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,

    // Latest raw 20-byte frames per opcode for Live Hex Inspector
    val latestFrame0x10: ByteArray? = null,
    val latestFrame0x13: ByteArray? = null,
    val latestFrame0x7D: ByteArray? = null,
    val latestFrame0x11: ByteArray? = null,
    val packetCount0x10: Int = 0,
    val packetCount0x13: Int = 0,
    val packetCount0x7D: Int = 0,
    val packetCount0x11: Int = 0,
    val malformedPacketCount: Int = 0,
    val lastPacketTimestampMs: Long? = null
)

/**
 * Outgoing 20-byte command packet entry for the serialized FIFO Write Queue on 0x5352 (Handle 0x0022).
 */
data class QueuedWriteCommand(
    val id: Long,
    val label: String,
    val payload: ByteArray,
    val enqueuedAtMs: Long,
    val status: WriteCommandStatus = WriteCommandStatus.QUEUED,
    val ackLatencyMs: Long? = null,
    val errorDetail: String? = null
)

enum class WriteCommandStatus {
    QUEUED,
    AWAITING_ACK,
    ACK_RECEIVED,
    TIMEOUT_500MS,
    REJECTED_FRAMING,
    DISCONNECTED_ERROR
}

/**
 * GPS + BLE fused telemetry sample and Last Parked Location record.
 */
data class LastParkedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val batteryPercentAtPark: Int?,
    val rangeKmAtPark: Int?,
    val odometerKmAtPark: Double?
)

data class GpsBleFusionSample(
    val timestampMs: Long,
    val wheelSpeedKmh: Int?,
    val gpsSpeedKmh: Double,
    val deltaKmh: Double?,
    val batteryPercent: Int?,
    val latitude: Double,
    val longitude: Double
)

fun ByteArray.toHexString(separator: String = " "): String =
    joinToString(separator) { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

fun ByteArray.toAsciiPreview(): String =
    map { b ->
        val code = b.toInt() and 0xFF
        if (code in 32..126) code.toChar() else '.'
    }.joinToString("")
