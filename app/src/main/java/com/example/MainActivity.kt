package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.DashboardDestination
import com.example.ui.IqubeDashboardViewModel
import com.example.ui.components.TopConnectionHeader
import com.example.ui.screens.CockpitDashboardScreen
import com.example.ui.screens.CommandStudioScreen
import com.example.ui.screens.GpsRideLoggerScreen
import com.example.ui.screens.HexInspectorScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                IqubeCompanionApp()
            }
        }
    }
}

@Composable
fun IqubeCompanionApp(
    viewModel: IqubeDashboardViewModel = viewModel()
) {
    val currentDestination by viewModel.currentDestination.collectAsStateWithLifecycle()
    val connectionPhase by viewModel.connectionPhase.collectAsStateWithLifecycle()
    val deviceName by viewModel.connectedDeviceName.collectAsStateWithLifecycle()
    val deviceMac by viewModel.connectedDeviceMac.collectAsStateWithLifecycle()
    val rssiDbm by viewModel.rssiDbm.collectAsStateWithLifecycle()
    val negotiatedMtu by viewModel.negotiatedMtu.collectAsStateWithLifecycle()
    val isLinkDegraded by viewModel.isLinkDegraded.collectAsStateWithLifecycle()
    val linkWarningMessage by viewModel.linkWarningMessage.collectAsStateWithLifecycle()
    val autoReconnectEnabled by viewModel.autoReconnectEnabled.collectAsStateWithLifecycle()
    val heartbeatEnabled by viewModel.heartbeatEnabled.collectAsStateWithLifecycle()
    val fragmentedSliceTestEnabled by viewModel.fragmentedSliceTestEnabled.collectAsStateWithLifecycle()

    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val byteOffsetMapping by viewModel.byteOffsetMapping.collectAsStateWithLifecycle()
    val commandHistory by viewModel.commandHistory.collectAsStateWithLifecycle()
    val queueDepth by viewModel.queueDepth.collectAsStateWithLifecycle()

    val currentGpsSpeedKmh by viewModel.currentGpsSpeedKmh.collectAsStateWithLifecycle()
    val lastParkedLocation by viewModel.lastParkedLocation.collectAsStateWithLifecycle()
    val gpsFusionSamples by viewModel.gpsFusionSamples.collectAsStateWithLifecycle()

    // Runtime BLE & Location permissions launcher before scanning real BLE hardware
    val blePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.startBleScanAndConnect()
    }

    val triggerBleScanWithPermissions = {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        blePermissionsLauncher.launch(requiredPermissions)
    }

    // BackHandler on sub-screens to return to Cockpit Dashboard
    BackHandler(enabled = currentDestination != DashboardDestination.COCKPIT) {
        viewModel.navigateTo(DashboardDestination.COCKPIT)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val isWideScreen = maxWidth >= 640.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!isWideScreen) {
                    IqubeBottomNavigationBar(
                        currentDestination = currentDestination,
                        onSelectDestination = viewModel::navigateTo
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    IqubeSideNavigationRail(
                        currentDestination = currentDestination,
                        onSelectDestination = viewModel::navigateTo
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    TopConnectionHeader(
                        connectionPhase = connectionPhase,
                        deviceName = deviceName,
                        deviceMac = deviceMac,
                        rssiDbm = rssiDbm,
                        negotiatedMtu = negotiatedMtu,
                        isIgnitionUnlocked = telemetry.isIgnitionUnlocked,
                        rideMode = telemetry.rideMode,
                        isLinkDegraded = isLinkDegraded,
                        linkWarningMessage = linkWarningMessage,
                        onConnectBleClick = triggerBleScanWithPermissions,
                        onReplayHciClick = viewModel::startHciLogReplay,
                        onDisconnectClick = viewModel::disconnectBle
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when (currentDestination) {
                            DashboardDestination.COCKPIT -> {
                                CockpitDashboardScreen(
                                    telemetry = telemetry,
                                    onSelectTripTab = viewModel::setActiveTripTab,
                                    onResetTripClick = viewModel::sendResetTripCommand,
                                    onCycleTripClick = viewModel::sendCycleTripCommand,
                                    onPushSampleNavClick = { icon, dist, road ->
                                        viewModel.sendNavigationInstruction(icon, dist, road, false)
                                    },
                                    onSendSampleCallAlertClick = viewModel::sendCallerOrAlertBanner,
                                    onToggleLampClick = viewModel::sendToggleDashboardLampCommand,
                                    onToggleChargingClick = viewModel::toggleReplayChargingState
                                )
                            }
                            DashboardDestination.COMMAND_STUDIO -> {
                                CommandStudioScreen(
                                    commandHistory = commandHistory,
                                    queueDepth = queueDepth,
                                    heartbeatEnabled = heartbeatEnabled,
                                    onSetHeartbeatEnabled = viewModel::setHeartbeatEnabled,
                                    onSendNavigation = viewModel::sendNavigationInstruction,
                                    onSendCallerAlert = viewModel::sendCallerOrAlertBanner,
                                    onSendCustomHex = viewModel::sendCustomHexFrame
                                )
                            }
                            DashboardDestination.GPS_LOGGER -> {
                                GpsRideLoggerScreen(
                                    telemetry = telemetry,
                                    currentGpsSpeedKmh = currentGpsSpeedKmh,
                                    lastParkedLocation = lastParkedLocation,
                                    gpsFusionSamples = gpsFusionSamples,
                                    autoReconnectEnabled = autoReconnectEnabled,
                                    onSetAutoReconnect = viewModel::setAutoReconnect,
                                    onStartGpsTracking = viewModel::startGpsTelemetryFusion
                                )
                            }
                            DashboardDestination.HEX_INSPECTOR -> {
                                HexInspectorScreen(
                                    telemetry = telemetry,
                                    byteOffsetMapping = byteOffsetMapping,
                                    fragmentedSliceTestEnabled = fragmentedSliceTestEnabled,
                                    onUpdateMapping = viewModel::updateByteOffsetMapping,
                                    onSetFragmentedSliceTest = viewModel::setFragmentedSliceTestEnabled
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IqubeBottomNavigationBar(
    currentDestination: DashboardDestination,
    onSelectDestination: (DashboardDestination) -> Unit
) {
    NavigationBar(
        containerColor = SlateSurface,
        contentColor = TextPrimary
    ) {
        DashboardDestination.entries.forEach { dest ->
            val selected = currentDestination == dest
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectDestination(dest) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(dest, selected),
                        contentDescription = dest.title
                    )
                },
                label = { Text(dest.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ElectricCyan,
                    selectedTextColor = ElectricCyan,
                    indicatorColor = SlateCardElevated,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                ),
                modifier = Modifier.testTag("nav_tab_${dest.route}")
            )
        }
    }
}

@Composable
private fun IqubeSideNavigationRail(
    currentDestination: DashboardDestination,
    onSelectDestination: (DashboardDestination) -> Unit
) {
    NavigationRail(
        containerColor = SlateSurface,
        contentColor = TextPrimary,
        modifier = Modifier.fillMaxHeight()
    ) {
        DashboardDestination.entries.forEach { dest ->
            val selected = currentDestination == dest
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectDestination(dest) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(dest, selected),
                        contentDescription = dest.title
                    )
                },
                label = { Text(dest.title) },
                modifier = Modifier.testTag("rail_tab_${dest.route}")
            )
        }
    }
}

private fun destinationIcon(dest: DashboardDestination, selected: Boolean) = when (dest) {
    DashboardDestination.COCKPIT -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
    DashboardDestination.COMMAND_STUDIO -> if (selected) Icons.Filled.Tune else Icons.Outlined.Tune
    DashboardDestination.GPS_LOGGER -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
    DashboardDestination.HEX_INSPECTOR -> if (selected) Icons.Filled.Code else Icons.Outlined.Code
}
