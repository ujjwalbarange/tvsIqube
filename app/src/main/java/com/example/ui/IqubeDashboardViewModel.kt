package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import com.example.model.ByteOffsetMapping
import com.example.model.ClusterAlertType
import com.example.model.GpsBleFusionSample
import com.example.model.LastParkedLocation
import com.example.model.NavigationTurnIcon
import com.example.model.QueuedWriteCommand
import com.example.model.TelemetryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashboardDestination(val route: String, val title: String) {
    COCKPIT("cockpit", "Cockpit"),
    COMMAND_STUDIO("command_studio", "Cluster Studio"),
    GPS_LOGGER("gps_logger", "Ride & Park"),
    HEX_INSPECTOR("hex_inspector", "Hex Inspector")
}

class IqubeDashboardViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = IqubeBleManager(application.applicationContext)

    private val _currentDestination = MutableStateFlow(DashboardDestination.COCKPIT)
    val currentDestination: StateFlow<DashboardDestination> = _currentDestination.asStateFlow()

    val connectionPhase: StateFlow<BleConnectionPhase> = bleManager.connectionPhase
    val connectedDeviceName: StateFlow<String> = bleManager.connectedDeviceName
    val connectedDeviceMac: StateFlow<String> = bleManager.connectedDeviceMac
    val rssiDbm: StateFlow<Int?> = bleManager.rssiDbm
    val negotiatedMtu: StateFlow<Int?> = bleManager.negotiatedMtu
    val discoveredServiceUuid: StateFlow<String> = bleManager.discoveredServiceUuid
    val isLinkDegraded: StateFlow<Boolean> = bleManager.isLinkDegraded
    val linkWarningMessage: StateFlow<String?> = bleManager.linkWarningMessage
    val autoReconnectEnabled: StateFlow<Boolean> = bleManager.autoReconnectEnabled
    val heartbeatEnabled: StateFlow<Boolean> = bleManager.heartbeatEnabled
    val fragmentedSliceTestEnabled: StateFlow<Boolean> = bleManager.fragmentedSliceTestEnabled

    val telemetryState: StateFlow<TelemetryState> = bleManager.packetParser.telemetryState
    val byteOffsetMapping: StateFlow<ByteOffsetMapping> = bleManager.packetParser.byteOffsetMapping

    val commandHistory: StateFlow<List<QueuedWriteCommand>> = bleManager.commandDispatcher.commandHistory
    val queueDepth: StateFlow<Int> = bleManager.commandDispatcher.queueDepth

    val currentGpsSpeedKmh: StateFlow<Double?> = bleManager.currentGpsSpeedKmh
    val lastParkedLocation: StateFlow<LastParkedLocation?> = bleManager.lastParkedLocation
    val gpsFusionSamples: StateFlow<List<GpsBleFusionSample>> = bleManager.gpsFusionSamples

    fun navigateTo(destination: DashboardDestination) {
        _currentDestination.value = destination
    }

    fun startBleScanAndConnect() {
        bleManager.startBleScanAndConnect()
    }

    fun startHciLogReplay() {
        bleManager.startBtsnoozHciLogReplay()
    }

    fun disconnectBle() {
        bleManager.disconnectBle(userInitiated = true)
    }

    fun setActiveTripTab(tabIndex: Int) {
        bleManager.packetParser.setActiveTripTab(tabIndex)
    }

    fun sendCycleTripCommand() {
        bleManager.sendCycleTripCommand()
    }

    fun sendResetTripCommand() {
        bleManager.sendResetTripCommand()
    }

    fun sendToggleDashboardLampCommand() {
        bleManager.sendToggleDashboardLampCommand()
    }

    fun sendNavigationInstruction(
        turnIcon: NavigationTurnIcon,
        distanceMeters: Int,
        roadName: String,
        use5AEnvelope: Boolean = false
    ) {
        bleManager.sendNavigationInstruction(turnIcon, distanceMeters, roadName, use5AEnvelope)
    }

    fun sendCallerOrAlertBanner(alertType: ClusterAlertType, callerText: String) {
        bleManager.sendCallerOrAlertBanner(alertType, callerText)
    }

    fun sendCustomHexFrame(label: String, hexString: String): Result<Unit> {
        val cleanHex = hexString.trim().replace("0x", "", ignoreCase = true)
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }

        if (cleanHex.size != 20) {
            return Result.failure(
                IllegalArgumentException("Expected 20 hex bytes separated by spaces (got ${cleanHex.size}).")
            )
        }
        val bytes = ByteArray(20)
        for (i in 0 until 20) {
            val parsed = cleanHex[i].toIntOrNull(16)
                ?: return Result.failure(IllegalArgumentException("Invalid hex byte '${cleanHex[i]}' at index $i."))
            if (parsed !in 0..255) {
                return Result.failure(IllegalArgumentException("Byte out of range at index $i."))
            }
            bytes[i] = parsed.toByte()
        }
        return bleManager.sendCustomVerifiedHexFrame(label, bytes)
    }

    fun updateByteOffsetMapping(newMapping: ByteOffsetMapping) {
        bleManager.packetParser.updateByteOffsetMapping(newMapping)
    }

    fun setAutoReconnect(enabled: Boolean) {
        bleManager.setAutoReconnect(enabled)
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        bleManager.setHeartbeatEnabled(enabled)
    }

    fun setFragmentedSliceTestEnabled(enabled: Boolean) {
        bleManager.setFragmentedSliceTestEnabled(enabled)
    }

    fun toggleReplayChargingState() {
        bleManager.toggleReplayChargingState()
    }

    fun startGpsTelemetryFusion() {
        bleManager.startGpsTelemetryFusion()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnectBle(userInitiated = true)
    }
}
