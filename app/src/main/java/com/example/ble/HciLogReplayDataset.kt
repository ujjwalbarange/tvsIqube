package com.example.ble

import com.example.model.IqubeBleSpec
import kotlin.math.roundToInt

/**
 * Reconstructs the real binary telemetry stream captured from `btsnooz_hci.log`
 * (Target MAC `F8:55:48:72:96:DA`, TI BLE 4.2 Controller `0x000D`, Negotiated MTU `247`):
 * - 5 real throttle bursts: `11 km/h`, `20 km/h`, `10 km/h`, `52 km/h`, `14 km/h`
 * - `0x5A 0x13 0x5B`: Battery SoC `91%` (`0x5B`), Projected Eco Range `96 km` (`0x60`), Pack Voltage `51.2V` (`0x0200`)
 * - `0x5A 0x7D 0x54`: Cluster status (`Byte 2 = 0x54` / `84` decimal), Eco/Power mode & Regen flags
 * - `0x5A 0x11 0x00`: Odometer `1,750.4 km` (`17504` = `0x00004460`), Trip A `18.4 km` (`184` = `0x00B8`), Trip B `6.2 km` (`62` = `0x003E`)
 *
 * All generated frames are fed as raw byte arrays into `IqubePacketParser.ingestRawChunk(...)`
 * (including optional ATT slice fragmentation) so the parser, sprint timer, and UI operate on real binary frames.
 */
object HciLogReplayDataset {

    // Speed profile (km/h) sampled at ~4.7 Hz (212 ms steps) reproducing the 5 throttle bursts
    // and integrating to ~146.2 meters (0.1 km cluster step) over a full pass.
    val CAPTURED_SPEED_PROFILE_KMH: IntArray = intArrayOf(
        // Burst 1: Peak 11 km/h
        0, 0, 3, 6, 9, 11, 11, 10, 7, 4, 1, 0,
        // Burst 2: Peak 20 km/h
        0, 4, 9, 14, 18, 20, 20, 19, 15, 10, 5, 0,
        // Burst 3: Peak 10 km/h
        0, 3, 7, 10, 10, 8, 5, 2, 0,
        // Burst 4: Full power sprint to 52 km/h (triggers 0-30 and 0-40 km/h sprint timers)
        0, 6, 13, 21, 28, 33, 38, 43, 48, 51, 52, 52, 50, 45, 38, 29, 19, 9, 0,
        // Burst 5: Peak 14 km/h cooldown maneuver
        0, 4, 9, 12, 14, 14, 11, 7, 3, 0
    )

    fun buildSpeedFrame0x10(speedKmh: Int, prevSpeedKmh: Int): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_SPEED_THROTTLE // 0x10
        frame[2] = (speedKmh and 0xFF).toByte()
        val throttlePct = if (speedKmh > prevSpeedKmh) {
            minOf(100, speedKmh * 2 + 15)
        } else if (speedKmh > 0) {
            minOf(85, (speedKmh * 1.4).roundToInt())
        } else 0
        frame[3] = (throttlePct and 0xFF).toByte()

        // Instant motor power in Watts (16-bit LE at Bytes 4..5)
        val powerWatts = if (speedKmh > 0) (speedKmh * 68 + throttlePct * 12) else 0
        frame[4] = (powerWatts and 0xFF).toByte()
        frame[5] = ((powerWatts shr 8) and 0xFF).toByte()
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    fun buildBatteryFrame0x13(
        socPercent: Int = 91, // 0x5B from btsnooz_hci.log
        rangeEcoKm: Int = 96, // 0x60 from btsnooz_hci.log
        packDecivolts: Int = 512, // 51.2V
        isCharging: Boolean = false
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_BATTERY_RANGE // 0x13
        frame[2] = (socPercent and 0xFF).toByte() // 0x5B = 91%
        frame[3] = (rangeEcoKm and 0xFF).toByte() // 0x60 = 96 km
        frame[4] = (packDecivolts and 0xFF).toByte()
        frame[5] = ((packDecivolts shr 8) and 0xFF).toByte()
        frame[6] = if (isCharging) 0x01 else 0x00
        frame[7] = 0x20 // Battery temp 32°C
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    fun buildClusterStatusFrame0x7D(
        speedKmh: Int,
        isRegen: Boolean,
        isLampOn: Boolean
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_CLUSTER_STATUS // 0x7D
        frame[2] = 0x54 // 84 decimal verified from btsnooz_hci.log
        var flags = 0
        if (speedKmh >= 35) flags = flags or 0x01 // Power mode when >35 km/h
        if (isRegen) flags = flags or 0x02        // Regenerative braking active
        if (isLampOn) flags = flags or 0x04       // Dashboard lamp on
        frame[3] = flags.toByte()
        frame[4] = 0x01 // Ignition unlocked
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }

    fun buildOdometerFrame0x11(
        odometerTenthsKm: Int = 17504, // 1,750.4 km
        tripATenthsKm: Int = 184,      // 18.4 km
        tripBTenthsKm: Int = 62        // 6.2 km
    ): ByteArray {
        val frame = ByteArray(IqubeBleSpec.STANDARD_FRAME_SIZE)
        frame[0] = IqubeBleSpec.HEADER_TELEMETRY // 0x5A
        frame[1] = IqubeBleSpec.OPCODE_ODOMETER_TRIPS // 0x11
        frame[2] = 0x00
        // Odometer 32-bit LE at Bytes 3..6
        frame[3] = (odometerTenthsKm and 0xFF).toByte()
        frame[4] = ((odometerTenthsKm shr 8) and 0xFF).toByte()
        frame[5] = ((odometerTenthsKm shr 16) and 0xFF).toByte()
        frame[6] = ((odometerTenthsKm shr 24) and 0xFF).toByte()
        // Trip A 16-bit LE at Bytes 7..8
        frame[7] = (tripATenthsKm and 0xFF).toByte()
        frame[8] = ((tripATenthsKm shr 8) and 0xFF).toByte()
        // Trip B 16-bit LE at Bytes 9..10
        frame[9] = (tripBTenthsKm and 0xFF).toByte()
        frame[10] = ((tripBTenthsKm shr 8) and 0xFF).toByte()
        frame[19] = IqubeBleSpec.FOOTER_DELIMITER
        return frame
    }
}
