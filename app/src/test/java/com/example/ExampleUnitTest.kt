package com.example

import com.example.ble.HciLogReplayDataset
import com.example.ble.IqubeCommandDispatcher
import com.example.ble.IqubePacketParser
import com.example.model.ClusterAlertType
import com.example.model.NavigationTurnIcon
import com.example.model.RideMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun packetParser_startsWithNullTelemetryState_noMockData() {
        val parser = IqubePacketParser()
        val state = parser.telemetryState.value
        assertNull(state.batteryPercent)
        assertNull(state.speedKmh)
        assertNull(state.rangeEcoKm)
        assertNull(state.odometerKm)
        assertNull(state.tripAKm)
    }

    @Test
    fun packetParser_reassemblesFragmentedAttSlicesAndDecodesAllOpcodes() {
        val parser = IqubePacketParser()

        // 1. Fragmented 0x13 Battery frame (8B + 12B slices)
        val batteryFrame = HciLogReplayDataset.buildBatteryFrame0x13(
            socPercent = 91,
            rangeEcoKm = 96,
            packDecivolts = 512,
            isCharging = false
        )
        val slice1 = batteryFrame.copyOfRange(0, 8)
        val slice2 = batteryFrame.copyOfRange(8, 20)

        val firstResult = parser.ingestRawChunk(slice1, 1000L)
        assertTrue("Incomplete slice must wait for remaining bytes", firstResult.isEmpty())

        val secondResult = parser.ingestRawChunk(slice2, 1010L)
        assertEquals(1, secondResult.size)

        val stateAfterBattery = parser.telemetryState.value
        assertEquals(91, stateAfterBattery.batteryPercent)
        assertEquals(96, stateAfterBattery.rangeEcoKm)
        assertEquals(51.2f, stateAfterBattery.packVoltageVolts ?: 0f, 0.05f)

        // 2. Speed 0x10 frame integration & 0-30 / 0-40 sprint timer latch
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedFrame0x10(0, 0), 2000L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedFrame0x10(15, 0), 3000L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedFrame0x10(32, 15), 5200L)
        parser.ingestRawChunk(HciLogReplayDataset.buildSpeedFrame0x10(44, 32), 6800L)

        val stateAfterSprint = parser.telemetryState.value
        assertEquals(44, stateAfterSprint.speedKmh)
        assertEquals(44, stateAfterSprint.peakSpeedKmh)
        assertNotNull("0-30 km/h sprint timer should latch", stateAfterSprint.sprint0To30Sec)
        assertNotNull("0-40 km/h sprint timer should latch", stateAfterSprint.sprint0To40Sec)
        assertTrue(stateAfterSprint.integratedDistanceMeters > 0.0)

        // 3. Cluster status 0x7D frame
        parser.ingestRawChunk(HciLogReplayDataset.buildClusterStatusFrame0x7D(44, true, true), 7000L)
        val stateAfter7D = parser.telemetryState.value
        assertEquals(0x54, stateAfter7D.clusterIdentityByte)
        assertEquals(RideMode.POWER, stateAfter7D.rideMode)
        assertTrue(stateAfter7D.isRegenActive)
        assertTrue(stateAfter7D.isDashboardLampOn)

        // 4. Odometer & Trip 0x11 frame
        parser.ingestRawChunk(
            HciLogReplayDataset.buildOdometerFrame0x11(
                odometerTenthsKm = 17504,
                tripATenthsKm = 184,
                tripBTenthsKm = 62
            ),
            8000L
        )
        val stateAfter11 = parser.telemetryState.value
        assertEquals(1750.4, stateAfter11.odometerKm ?: 0.0, 0.01)
        assertEquals(18.4, stateAfter11.tripAKm ?: 0.0, 0.01)
        assertEquals(6.2, stateAfter11.tripBKm ?: 0.0, 0.01)
    }

    @Test
    fun commandDispatcher_validatesFramingAndRejectsArbitraryBytes() {
        val dispatcher = IqubeCommandDispatcher(
            scope = CoroutineScope(Dispatchers.Unconfined),
            performGattWrite = { true },
            onWriteAckTimeout = {}
        )

        // Valid Trip Reset frame (5A F1 00 02 ... FF)
        val tripReset = dispatcher.buildTripControlFrame(0x02)
        assertTrue(dispatcher.validateOutgoingFrame(tripReset).isSuccess)

        // Valid Navigation frame (5B 5C 57 ...)
        val navFrame = dispatcher.buildNavigationFrame(NavigationTurnIcon.TURN_LEFT, 150, "MG ROAD")
        assertTrue(dispatcher.validateOutgoingFrame(navFrame).isSuccess)

        // Valid Caller Alert frame (5B 5D 43 ...)
        val alertFrame = dispatcher.buildCallerAlertFrame(ClusterAlertType.INCOMING_CALL, "RAHUL")
        assertTrue(dispatcher.validateOutgoingFrame(alertFrame).isSuccess)

        // Reject arbitrary unverified header byte
        val arbitrary = ByteArray(20) { 0x12 }
        assertTrue(dispatcher.validateOutgoingFrame(arbitrary).isFailure)

        // Reject 0x5A frame missing 0xFF terminator at byte 19
        val missingFooter = tripReset.copyOf().apply { this[19] = 0x00 }
        assertTrue(dispatcher.validateOutgoingFrame(missingFooter).isFailure)
    }
}
