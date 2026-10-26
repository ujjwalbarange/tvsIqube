package com.example.model

import java.util.Locale

/**
 * Target BLE Hardware & UUID Constants for TVS iQube (SmartXonnect BLE Cluster)
 * Verified from unfiltered btsnoop_hci.log (#229, #270, #315) and btsnooz_hci.log.
 */
object IqubeBleSpec {
    const val TARGET_MAC_ADDRESS = "F8:55:48:72:96:DA"
    const val ADVERTISED_BLE_NAME = "TVSiQUBECJI573"
    const val DEFAULT_VEHICLE_ID = "TVSiQUBEJI573"
    const val CLUSTER_VEHICLE_ID_DEFAULT = "TVSiQUBEJI573"
    const val NAME_FILTER_PREFIX = "TVS"

    // Custom 128-bit SmartXonnect Primary Service UUID (ASCII: "TVSMVGSASBENTORQ")
    const val SERVICE_UUID_SMARTXONNECT = "5456534d-5647-5341-5342-454e544f5251"
    const val SERVICE_UUID_ASCII = "TVSMVGSASBENTORQ"
    // Telemetry Notify Characteristic (Handle 0x001E, 16-bit 0x5354, ASCII "ST")
    const val CHAR_UUID_TELEMETRY_NOTIFY = "00005354-0000-1000-8000-00805f9b34fb"
    // Command Write Characteristic (Handle 0x0022, 16-bit 0x5352, ASCII "SR")
    const val CHAR_UUID_COMMAND_WRITE = "00005352-0000-1000-8000-00805f9b34fb"
    const val DESCRIPTOR_UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb"       // Handle 0x001F
    const val DESCRIPTOR_UUID_CUD = "00002901-0000-1000-8000-00805f9b34fb"        // Handle 0x0023

    // Protocol Framing Bytes (Every 20-byte frame sent or received ends with Byte 19 = 0xFF)
    const val HEADER_TELEMETRY: Byte = 0x5A // 'Z' (90 decimal)
    const val HEADER_ASCII_CMD: Byte = 0x5B // '[' (91 decimal)
    const val FOOTER_DELIMITER: Byte = 0xFF.toByte()
    const val STANDARD_FRAME_SIZE = 20
    const val MAX_LOCATION_ASCII_BYTES = 17 // Bytes 2..18 (17 ASCII chars), Byte 19 = 0xFF

    // Incoming Telemetry Opcodes (Scooter -> Phone on 0x5354 / Handle 0x001E)
    const val OPCODE_SPEED_ODOMETER: Byte = 0x10 // Byte 2 = speed km/h, Bytes 3..5 = uint24 BE total odometer (0.1 km)
    const val OPCODE_TRIPS_CHARGING: Byte = 0x11 // Bytes 2..4 = Trip A (0.1 km), Byte 5 = Charging (0x01), Byte 6 = SoC mirror, Bytes 7..9 = Trip B (0.1 km)
    const val OPCODE_BATTERY_RANGE: Byte = 0x13  // Byte 2 = SoC %, Bytes 3..4 = uint16 BE Cluster Range (0.1 km), Byte 5 = State Flag (0x06 charging)
    const val OPCODE_VEHICLE_ID: Byte = 0x7D     // Bytes 2..18 = ASCII Vehicle ID ("TVSiQUBEJI573")

    // Verified Hardware Subcommands for 0x5A 0xF1 0x00 (Handle 0x0022, Byte 19 = 0xFF):
    const val SUBCMD_SWITCH_TRIP_A: Byte = 0x03
    const val SUBCMD_SWITCH_TRIP_B: Byte = 0x01
    const val SUBCMD_RESET_ACTIVE_TRIP: Byte = 0x02

    // Charging State Constants
    const val FLAG_0X13_CHARGING_MODE: Byte = 0x06
    const val FLAG_0X11_CHARGING_ACTIVE: Byte = 0x01
    const val VERIFIED_CAPTURE_SEC_PER_PCT = 115.7   // +31.1% / hr from btsnoop_hci.log charging capture
    const val DEFAULT_0_TO_80_SEC_PER_PCT = 123.75   // 2 hr 45 min (9,900s) for 0% -> 80%
    const val DEFAULT_CHARGING_SEC_PER_PCT = 123.75

    // Link & Timing Constants
    const val REQUESTED_MTU = 517
    const val EXPECTED_NEGOTIATED_MTU = 247
    const val WRITE_ACK_TIMEOUT_MS = 500L
    const val LINK_WATCHDOG_DEGRADED_MS = 2500L
    const val SUPERVISION_TIMEOUT_MS = 6000L
    const val HEARTBEAT_INTERVAL_MS = 1800L
    const val LOCATION_SYNC_INTERVAL_MS = 10_000L

    // True Range Baseline Constants
    const val DEFAULT_ECO_KM_PER_PCT = 1.00   // 100 km at 100% SoC
    const val DEFAULT_POWER_KM_PER_PCT = 0.75 // 75 km at 100% SoC
    const val POWER_TO_ECO_RATIO = 0.75
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
    REPLAYING_RIDING_LOG,
    REPLAYING_CHARGING_LOG,
    RECONNECTING_BACKOFF
}

enum class RideMode(val label: String) {
    ECO("ECO MODE"),
    POWER("POWER MODE"),
    CHARGING("CHARGING ACTIVE"),
    PARK("PARK / STANDBY")
}

/**
 * User-configurable Byte-Offset Mapper for incoming 20-byte frames.
 */
data class ByteOffsetMapping(
    val odometerStartOffsetIn0x10: Int = 3, // In 0x10 frame: Bytes 3..5 uint24 BE in 0.1 km (05 7B 54 = 35,925.2 km)
    val rangeStartOffsetIn0x13: Int = 3,    // In 0x13 frame: Bytes 3..4 uint16 BE in 0.1 km (01 90 = 40.0 km)
    val stateFlagOffsetIn0x13: Int = 5,     // In 0x13 frame: Byte 5 = 0x06 when charging
    val tripAStartOffsetIn0x11: Int = 2,    // In 0x11 frame: Bytes 2..4 uint24 BE in 0.1 km (00 00 03 = 0.3 km)
    val chargingFlagOffsetIn0x11: Int = 5,  // In 0x11 frame: Byte 5 = 0x01 when charging
    val socMirrorOffsetIn0x11: Int = 6,     // In 0x11 frame: Byte 6 = SoC % mirror
    val tripBStartOffsetIn0x11: Int = 7     // In 0x11 frame: Bytes 7..9 uint24 BE in 0.1 km (00 00 0A = 1.0 km)
)

/**
 * Editable subcommand configuration for the 3 verified Trip Control buttons (5A F1 00 <subCmd> ... FF).
 */
data class TripSubcommandConfig(
    val switchTripAByte: Int = IqubeBleSpec.SUBCMD_SWITCH_TRIP_A.toInt() and 0xFF, // 0x03
    val switchTripBByte: Int = IqubeBleSpec.SUBCMD_SWITCH_TRIP_B.toInt() and 0xFF, // 0x01
    val resetActiveTripByte: Int = IqubeBleSpec.SUBCMD_RESET_ACTIVE_TRIP.toInt() and 0xFF // 0x02
)

/**
 * Strongly-typed representation of a single decoded BLE packet from 0x5354 (Handle 0x001E).
 */
sealed class DecodedIqubePacket(
    open val timestampMs: Long,
    open val rawBytes: ByteArray
) {
    data class SpeedOdometerFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val speedKmh: Int,
        val totalOdometerKm: Double
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class BatteryRangeFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val batteryPercent: Int,
        val clusterRangeKm: Double,
        val stateFlagByte5: Int,
        val isChargingMode0x06: Boolean
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class TripsAndChargingFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val tripAKm: Double,
        val tripBKm: Double,
        val chargingActiveFlagByte5: Int,
        val isChargingActive: Boolean,
        val socMirrorPercent: Int
    ) : DecodedIqubePacket(timestampMs, rawBytes)

    data class VehicleIdentityFrame(
        override val timestampMs: Long,
        override val rawBytes: ByteArray,
        val vehicleIdAscii: String
    ) : DecodedIqubePacket(timestampMs, rawBytes)
}

/**
 * Complete reactive Telemetry State exposed to the Compose UI.
 */
data class TelemetryState(
    // Speed & Total Hardware Odometer (from 0x5A 0x10)
    val speedKmh: Int? = null,
    val peakSpeedKmh: Int = 0,
    val accelerationMs2: Double? = null,
    val integratedDistanceMeters: Double = 0.0,
    val clusterIntegratedStepKm: Double = 0.0,
    val sprint0To30Sec: Double? = null,
    val sprint0To40Sec: Double? = null,
    val isSprintTimerRunning: Boolean = false,
    val odometerKm: Double? = null,

    // Battery SoC, Cluster Range & Charging Flag (from 0x5A 0x13 & 0x5A 0x11)
    val batteryPercent: Int? = null,
    val socMirror0x11: Int? = null,
    val clusterRangeKm: Double? = null,
    val stateFlag0x13Byte5: Int? = null,
    val chargingFlag0x11Byte5: Int? = null,
    val isCharging: Boolean = false,
    val packVoltageVolts: Float? = null,

    // Hardware Trip A & Trip B (from 0x5A 0x11)
    val tripAKm: Double? = null,
    val tripBKm: Double? = null,
    val activeTripTab: Int = 0, // 0 = Trip A, 1 = Trip B, 2 = ODO, 3 = Sub-Meter
    val lastOdometerSyncMs: Long? = null,

    // Vehicle Model & ID String (from 0x5A 0x7D Bytes 2..18)
    val vehicleIdString: String? = null,
    val rideMode: RideMode? = null,
    val isRegenActive: Boolean = false,
    val isIgnitionUnlocked: Boolean? = null,

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
 * Live Charging Monitor & 3-Tier Charging Predictor State.
 * - Verified capture rate: `115.7s per +1%` (`+31.1%/hr`)
 * - Default 0-80% baseline: `123.75s per +1%` (`2h 45m` for `0-80%`, `+29.1%/hr`)
 * - Historical sessions & Live `+1%` step timing
 */
data class ChargingMonitorState(
    val isCharging: Boolean = false,
    val currentBatteryPct: Int? = null,
    val clusterRangeKm: Double? = null,
    val sessionStartBatteryPct: Int? = null,
    val pctGainedThisSession: Int = 0,
    val activeSecondsPerPct: Double = IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT,
    val baselineSecondsPerPct: Double = IqubeBleSpec.DEFAULT_CHARGING_SEC_PER_PCT,
    val liveMeasuredSecondsPerPct: Double? = null,
    val chargingSpeedPctPerHour: Double = 29.1,
    val remainingSecondsTo80: Long? = null,
    val remainingSecondsTo100: Long? = null,
    val baselineRemainingSecondsTo80: Long? = null,
    val baselineRemainingSecondsTo100: Long? = null,
    val predictionModeLabel: String = "Default Baseline (2h 45m 0–80% = 123.8s/% • Capture Ref: 115.7s/%)",
    val isUsingLiveStepTiming: Boolean = false,
    val completedStepsCount: Int = 0
)

data class SavedChargingSession(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startPct: Int,
    val endPct: Int,
    val pctGained: Int,
    val durationSeconds: Long,
    val avgSecondsPerPct: Double
)

/**
 * Offline / Disconnected "Last Seen" Scooter Snapshot persisted in local storage.
 */
data class LastSeenScooterState(
    val timestampMs: Long,
    val batteryPct: Int,
    val odometerKm: Double,
    val clusterRangeKm: Double,
    val tripAKm: Double,
    val tripBKm: Double,
    val locationName: String,
    val wasCharging: Boolean,
    val vehicleId: String
)

/**
 * Incoming Call Cluster Relay State (`0x5B 0x5C` with Caller's 1st Name + Call Symbol).
 */
data class IncomingCallRelayState(
    val isRinging: Boolean = false,
    val fullCallerName: String = "Rahul Sharma",
    val extractedFirstName: String = "Rahul",
    val clusterFormattedText: String = "CALL:Rahul",
    val ringStartTimeMs: Long? = null
)

/**
 * True Range Calculation Output (Eco Mode vs. Power Mode + Learned Trip Efficiency).
 */
data class TrueRangeCalculation(
    val trueEcoRangeKm: Double?,
    val truePowerRangeKm: Double?,
    val ecoKmPerPct: Double = IqubeBleSpec.DEFAULT_ECO_KM_PER_PCT,
    val powerKmPerPct: Double = IqubeBleSpec.DEFAULT_POWER_KM_PER_PCT,
    val totalDistanceLearnedKm: Double = 0.0,
    val totalBatteryUsedLearnedPct: Int = 0,
    val contributingTripsCount: Int = 0,
    val isUsingLearnedEfficiency: Boolean = false,
    val calculationSourceLabel: String = "Default Baseline (Eco 1.00 km/% • Power 0.75 km/%)"
)

data class ActiveTripSession(
    val startTimeMs: Long,
    val startLocationName: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val startOdometerKm: Double?,
    val startBatteryPct: Int?,
    val speedSamplesSum: Long = 0L,
    val speedSamplesCount: Int = 0,
    val peakSpeedKmh: Int = 0,
    val dominantRideMode: RideMode = RideMode.ECO
)

data class SavedTripRecord(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSeconds: Long,
    val startLocationName: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLocationName: String,
    val endLatitude: Double,
    val endLongitude: Double,
    val distanceKm: Double,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val batteryUsedPct: Int,
    val peakSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val rideMode: RideMode,
    val efficiencyKmPerPct: Double,
    val disconnectReason: String
)

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

data class LastParkedLocation(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val batteryPercentAtPark: Int?,
    val rangeKmAtPark: Double?,
    val odometerKmAtPark: Double?
)

data class GpsBleFusionSample(
    val timestampMs: Long,
    val wheelSpeedKmh: Int?,
    val gpsSpeedKmh: Double,
    val deltaKmh: Double?,
    val batteryPercent: Int?,
    val latitude: Double,
    val longitude: Double,
    val localityName: String
)

fun ByteArray.toHexString(separator: String = " "): String =
    joinToString(separator) { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

fun ByteArray.toAsciiPreview(): String =
    map { b ->
        val code = b.toInt() and 0xFF
        if (code in 32..126) code.toChar() else '.'
    }.joinToString("")
