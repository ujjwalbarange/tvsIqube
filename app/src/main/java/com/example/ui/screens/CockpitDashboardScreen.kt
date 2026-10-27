package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ChargingMonitorState
import com.example.model.IncomingCallRelayState
import com.example.model.IqubeBleSpec
import com.example.model.LastSeenScooterState
import com.example.model.NavigationManeuverState
import com.example.model.RideMode
import com.example.model.TelemetryState
import com.example.model.TripSubcommandConfig
import com.example.model.TrueRangeCalculation
import com.example.model.TurnTypeConstant
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitDashboardScreen(
    telemetry: TelemetryState,
    trueRange: TrueRangeCalculation,
    chargingMonitor: ChargingMonitorState,
    lastSeenState: LastSeenScooterState?,
    incomingCallState: IncomingCallRelayState,
    navigationState: NavigationManeuverState,
    selectedRangeMode: RideMode,
    detectedLocalityName: String,
    customLocationOverride: String,
    lastSyncedClusterLocation: String?,
    autoLocationSyncEnabled: Boolean,
    tripSubcommandConfig: TripSubcommandConfig,
    onSelectRangeMode: (RideMode) -> Unit,
    onSelectTripTab: (Int) -> Unit,
    onSwitchToTripAClick: () -> Unit,
    onSwitchToTripBClick: () -> Unit,
    onResetActiveTripClick: () -> Unit,
    onDumpLocationToClusterClick: () -> Unit,
    onCustomLocationOverrideChange: (String) -> Unit,
    onSetAutoLocationSync: (Boolean) -> Unit,
    onTriggerIncomingCallClick: (String) -> Unit,
    onEndIncomingCallClick: () -> Unit,
    onSendNavigationManeuver: (TurnTypeConstant, Int, Byte, String) -> Unit,
    onToggleNavigationRouteSim: () -> Unit,
    onSimulateChargingStepClick: () -> Unit,
    onToggleChargingClick: () -> Unit,
    riderGreetingName: String = "Rider",
    musicTrackTitle: String = "Midnight City",
    onSendMusicTrackClick: (String) -> Unit = {},
    onSyncRiderGreetingClick: (String) -> Unit = {},
    onSendPhoneStatusRelayClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isOfflineUsingLastSeen = telemetry.batteryPercent == null && lastSeenState != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 0A. ACTIVE INCOMING CALL BANNER (When Ringing on Cluster 0x5B 0x5C)
            AnimatedVisibility(visible = incomingCallState.isRinging) {
                IncomingCallActiveBanner(
                    incomingCallState = incomingCallState,
                    onEndCallClick = onEndIncomingCallClick
                )
            }

            // 0B. OFFLINE / DISCONNECTED "LAST SEEN" SNAPSHOT BANNER
            AnimatedVisibility(visible = isOfflineUsingLastSeen && lastSeenState != null) {
                if (lastSeenState != null) {
                    OfflineLastSeenSnapshotCard(
                        lastSeen = lastSeenState,
                        trueRange = trueRange
                    )
                }
            }

            // 1. HERO VISUALIZER: Circular Battery SoC Arc Gauge + Cluster Range (0x13 Bytes 3..4) + True Range Engine
            HeroBatteryAndTrueRangeCard(
                telemetry = telemetry,
                lastSeenState = lastSeenState,
                trueRange = trueRange,
                selectedRangeMode = selectedRangeMode,
                onSelectRangeMode = onSelectRangeMode,
                onToggleChargingClick = onToggleChargingClick
            )

            // 2. LIVE CHARGING MONITOR VIEW (When 0x11 Byte 5 == 0x01 or 0x13 Byte 5 == 0x06)
            AnimatedVisibility(visible = telemetry.isCharging) {
                LiveChargingMonitorCard(
                    telemetry = telemetry,
                    chargingMonitor = chargingMonitor,
                    trueRange = trueRange,
                    onSimulateChargingStepClick = onSimulateChargingStepClick
                )
            }

            // 3. LIVE TELEMETRY STRIP: Speed + Odometer (0x10 Bytes 2..5) & Hardware Trip A / Trip B (0x11)
            LiveTelemetryStripSection(
                telemetry = telemetry,
                lastSeenState = lastSeenState,
                onSelectTripTab = onSelectTripTab
            )

            // 4. VERIFIED TRIP CONTROL DECK (Handle 0x0022: Switch to Trip A, Switch to Trip B, Reset Active Trip A/B)
            VerifiedTripControlsCard(
                tripSubcommandConfig = tripSubcommandConfig,
                onSwitchToTripAClick = onSwitchToTripAClick,
                onSwitchToTripBClick = onSwitchToTripBClick,
                onResetActiveTripClick = onResetActiveTripClick
            )

            // 5. TURN-BY-TURN NAVIGATION 2-PACKET INJECTOR (`0x5B 0x49` -> 400ms -> `0x5B 0x4E`)
            TurnByTurnNavigationInjectorCard(
                navigationState = navigationState,
                onSendManeuver = onSendNavigationManeuver,
                onToggleRouteSimulation = onToggleNavigationRouteSim
            )

            // 6. LIVE CLUSTER LOCATION SYNC CARD (0x5B 0x5C + 17B ASCII Locality Name + 0xFF)
            ClusterLocationSyncCard(
                detectedLocalityName = detectedLocalityName,
                customLocationOverride = customLocationOverride,
                lastSyncedClusterLocation = lastSyncedClusterLocation,
                autoLocationSyncEnabled = autoLocationSyncEnabled,
                isCallRinging = incomingCallState.isRinging,
                onCustomLocationOverrideChange = onCustomLocationOverrideChange,
                onSetAutoLocationSync = onSetAutoLocationSync,
                onDumpLocationNowClick = onDumpLocationToClusterClick
            )

            // 7. DEDICATED CALLER ID BANNER RELAY (`0x5B 0x43` + 500ms repeat timer)
            IncomingCallClusterRelayCard(
                incomingCallState = incomingCallState,
                detectedLocalityName = detectedLocalityName,
                onTriggerCall = onTriggerIncomingCallClick,
                onEndCall = onEndIncomingCallClick
            )

            // 8. MUSIC TRACK BANNER (`0x5B 0x62`), RIDER GREETING (`0x5B 0x52`) & PHONE BATTERY/SIGNAL RELAY (`0x5B 0x52 0x55`)
            ClusterMusicAndRiderGreetingCard(
                riderGreetingName = riderGreetingName,
                musicTrackTitle = musicTrackTitle,
                onSendMusicTrackClick = onSendMusicTrackClick,
                onSyncRiderGreetingClick = onSyncRiderGreetingClick,
                onSendPhoneStatusRelayClick = onSendPhoneStatusRelayClick
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun IncomingCallActiveBanner(
    incomingCallState: IncomingCallRelayState,
    onEndCallClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AmberWarning.copy(alpha = 0.18f))
            .border(1.5.dp, AmberWarning, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("incoming_call_active_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Incoming Call Active",
                tint = AmberWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "INCOMING CALL: ${incomingCallState.extractedFirstName.uppercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Cluster 0x5B 0x43 Active (500ms repeat timer): \"${incomingCallState.clusterFormattedText}\" + 0xFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarning
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onEndCallClick,
            colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral, contentColor = SlateSurface),
            modifier = Modifier.testTag("banner_end_call_button")
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = "End Call", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("End & Restore GPS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OfflineLastSeenSnapshotCard(
    lastSeen: LastSeenScooterState,
    trueRange: TrueRangeCalculation
) {
    val context = LocalContext.current
    val exactTimeStr = remember(lastSeen.timestampMs) {
        SimpleDateFormat("hh:mm a • dd MMM", Locale.US).format(Date(lastSeen.timestampMs))
    }
    val elapsedMinutes = max(0L, (System.currentTimeMillis() - lastSeen.timestampMs) / 60_000L)
    val relativeStr = when {
        elapsedMinutes == 0L -> "Just now"
        elapsedMinutes == 1L -> "1 min ago"
        elapsedMinutes < 60L -> "$elapsedMinutes mins ago"
        else -> "${elapsedMinutes / 60L}h ${elapsedMinutes % 60L}m ago"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SlateCardElevated)
            .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("offline_last_seen_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Offline Last Seen Snapshot",
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "OFFLINE LAST SEEN STATE (${lastSeen.vehicleId})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
            }
            Text(
                text = "Last seen $relativeStr ($exactTimeStr)",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
        }

        Text(
            text = "Location: ${lastSeen.locationName} (${String.format(Locale.US, "%.4f, %.4f", lastSeen.latitude, lastSeen.longitude)})  •  Last SoC: ${lastSeen.batteryPct}%  •  ODO: ${String.format(Locale.US, "%,.1f km", lastSeen.odometerKm)}  •  Cluster BMS DTE: ${String.format(Locale.US, "%.1f km", lastSeen.clusterRangeKm)}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Expected True Range at ${lastSeen.batteryPct}%: Eco ${trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"}  |  Power ${trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = NeonMint,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        val mapsUri = Uri.parse(
                            String.format(
                                Locale.US,
                                "https://www.google.com/maps?q=%.6f,%.6f",
                                lastSeen.latitude,
                                lastSeen.longitude
                            )
                        )
                        val intent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.testTag("find_my_scooter_maps_button")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Find My Scooter on Maps",
                    tint = ElectricCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Find My Scooter on Maps",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeroBatteryAndTrueRangeCard(
    telemetry: TelemetryState,
    lastSeenState: LastSeenScooterState?,
    trueRange: TrueRangeCalculation,
    selectedRangeMode: RideMode,
    onSelectRangeMode: (RideMode) -> Unit,
    onToggleChargingClick: () -> Unit
) {
    // Never blank out gauges when disconnected if Last Seen state is persisted
    val effectiveSoc = telemetry.batteryPercent ?: lastSeenState?.batteryPct
    val effectiveClusterRangeKm = telemetry.clusterRangeKm ?: lastSeenState?.clusterRangeKm
    val isLive = telemetry.batteryPercent != null

    val targetSweep = if (effectiveSoc != null) (effectiveSoc / 100f) * 260f else 0f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "soc_arc_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "charging_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "charging_pulse_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(SlateCardElevated, SlateSurface)
                )
            )
            .border(1.dp, if (telemetry.isCharging) NeonMint.copy(alpha = pulseAlpha) else SlateBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isLive) "BATTERY SoC (0x13) & TRUE RANGE" else "LAST SEEN BATTERY SoC & EXPECTED RANGE",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (telemetry.isCharging) NeonMint.copy(alpha = 0.18f) else SlateSurface)
                    .border(
                        1.dp,
                        if (telemetry.isCharging) NeonMint.copy(alpha = pulseAlpha) else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(onClick = onToggleChargingClick)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("charging_state_pill"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Charging State",
                    tint = if (telemetry.isCharging) NeonMint else TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (telemetry.isCharging) {
                        "CHARGING ACTIVE (0x06 / 0x01)"
                    } else {
                        "TAP FOR CHARGE REPLAY"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (telemetry.isCharging) NeonMint else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Circular Arc Gauge
        Box(
            modifier = Modifier.size(205.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 16.dp.toPx()
                val inset = strokeWidth / 2f + 8.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)

                drawArc(
                    color = SlateBorder,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (animatedSweep > 0.5f) {
                    val arcBrush = Brush.sweepGradient(
                        colors = listOf(ElectricCyan, NeonMint, ElectricCyan)
                    )
                    drawArc(
                        brush = arcBrush,
                        startAngle = 140f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = effectiveSoc?.let { "$it %" } ?: "-- %",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (effectiveSoc != null) TextPrimary else TextMuted,
                    modifier = Modifier.testTag("battery_soc_text")
                )
                Spacer(modifier = Modifier.height(2.dp))
                val displayedTrueRange = if (selectedRangeMode == RideMode.POWER) {
                    trueRange.truePowerRangeKm
                } else {
                    trueRange.trueEcoRangeKm
                }
                Text(
                    text = displayedTrueRange?.let {
                        String.format(Locale.US, "TRUE %s: %.1f KM", selectedRangeMode.name, it)
                    } ?: "TRUE RANGE: --.- KM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedRangeMode == RideMode.POWER) EnergeticCoral else NeonMint,
                    modifier = Modifier.testTag("estimated_range_text")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = effectiveClusterRangeKm?.let {
                        String.format(Locale.US, "Scooter Cluster DTE (BMS Estimate): %.1f km", it)
                    } ?: "Scooter Cluster DTE (BMS): --.- km",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    modifier = Modifier.testTag("cluster_bms_dte_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // True Range Eco vs. Power Mode Selector & Efficiency Breakdown
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isEcoSelected = selectedRangeMode != RideMode.POWER
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEcoSelected) NeonMint.copy(alpha = 0.16f) else SlateSurface)
                    .border(
                        1.dp,
                        if (isEcoSelected) NeonMint else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectRangeMode(RideMode.ECO) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("true_range_eco_card")
            ) {
                Column {
                    Text(
                        text = "TRUE RANGE (ECO)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEcoSelected) NeonMint else TextSecondary
                    )
                    Text(
                        text = trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = String.format(Locale.US, "Rate: %.2f km / 1%%", trueRange.ecoKmPerPct),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonMint
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!isEcoSelected) EnergeticCoral.copy(alpha = 0.16f) else SlateSurface)
                    .border(
                        1.dp,
                        if (!isEcoSelected) EnergeticCoral else SlateBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectRangeMode(RideMode.POWER) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("true_range_power_card")
            ) {
                Column {
                    Text(
                        text = "TRUE RANGE (POWER)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!isEcoSelected) EnergeticCoral else TextSecondary
                    )
                    Text(
                        text = trueRange.truePowerRangeKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = String.format(Locale.US, "Rate: %.2f km / 1%%", trueRange.powerKmPerPct),
                        style = MaterialTheme.typography.labelSmall,
                        color = EnergeticCoral
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // True Range Data Source Badge + 0x13/0x11 Flags
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SlateSurface)
                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trueRange.calculationSourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (trueRange.isUsingLearnedEfficiency) NeonMint else ElectricCyan,
                modifier = Modifier.testTag("true_range_source_label")
            )
            val flag0x13 = telemetry.stateFlag0x13Byte5?.let { String.format("0x%02X", it) } ?: "--"
            val flag0x11 = telemetry.chargingFlag0x11Byte5?.let { String.format("0x%02X", it) } ?: "--"
            Text(
                text = "13[B5]:$flag0x13 • 11[B5]:$flag0x11",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (telemetry.isCharging) NeonMint else TextSecondary
            )
        }
    }
}

@Composable
private fun LiveChargingMonitorCard(
    telemetry: TelemetryState,
    chargingMonitor: ChargingMonitorState,
    trueRange: TrueRangeCalculation,
    onSimulateChargingStepClick: () -> Unit
) {
    val etaFmt = remember { SimpleDateFormat("hh:mm a", Locale.US) }
    val nowMs = System.currentTimeMillis()

    val eta80Clock = chargingMonitor.remainingSecondsTo80?.let {
        etaFmt.format(Date(nowMs + it * 1000L))
    } ?: "--:--"
    val eta100Clock = chargingMonitor.remainingSecondsTo100?.let {
        etaFmt.format(Date(nowMs + it * 1000L))
    } ?: "--:--"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.5.dp, NeonMint.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("live_charging_monitor_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Charging Active",
                    tint = NeonMint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "CHARGING ACTIVE • 3-TIER TIME PREDICTOR",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonMint
                    )
                    Text(
                        text = chargingMonitor.predictionModeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chargingMonitor.isUsingLiveStepTiming) ElectricCyan else AmberWarning
                    )
                }
            }
            OutlinedButton(
                onClick = onSimulateChargingStepClick,
                modifier = Modifier.testTag("simulate_1pct_step_button")
            ) {
                Text("+1% Step (115.7s)", color = NeonMint, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Live SoC, Cluster Range (0x13), True Eco Range & Speed (+31.1%/hr = 115.7s/%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("LIVE SoC & GAIN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "${chargingMonitor.currentBatteryPct ?: "--"}% (+${chargingMonitor.pctGainedThisSession}%)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonMint
                )
                Text(
                    text = "0x11 Mirror: ${telemetry.socMirror0x11 ?: "--"}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("CLUSTER vs TRUE ECO", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "${telemetry.clusterRangeKm?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} / ${trueRange.trueEcoRangeKm?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} km",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
                Text(
                    text = "0x13 B3..4 vs True Eco",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text("CHARGE SPEED", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    text = "+${chargingMonitor.chargingSpeedPctPerHour}% / hr",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
                Text(
                    text = "${chargingMonitor.activeSecondsPerPct}s per +1%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // Countdown & Clock ETAs for Estimated Time to 80% and Estimated Time to 100%
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "ESTIMATED TIME TO 80%",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = formatDurationSeconds(chargingMonitor.remainingSecondsTo80),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("charging_eta_80_text")
                )
                Text(
                    text = "80% at $eta80Clock  •  Base: ${formatDurationSeconds(chargingMonitor.baselineRemainingSecondsTo80)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, NeonMint.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "ESTIMATED TIME TO FULL (100%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
                Text(
                    text = formatDurationSeconds(chargingMonitor.remainingSecondsTo100),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.testTag("charging_eta_100_text")
                )
                Text(
                    text = "100% at $eta100Clock  •  Base: ${formatDurationSeconds(chargingMonitor.baselineRemainingSecondsTo100)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun formatDurationSeconds(seconds: Long?): String {
    if (seconds == null) return "--h --m"
    if (seconds <= 0L) return "REACHED"
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%dh %02dm", hrs, mins)
    } else {
        String.format(Locale.US, "%dm %02ds", mins, secs)
    }
}

@Composable
private fun LiveTelemetryStripSection(
    telemetry: TelemetryState,
    lastSeenState: LastSeenScooterState?,
    onSelectTripTab: (Int) -> Unit
) {
    val effectiveOdoKm = telemetry.odometerKm ?: lastSeenState?.odometerKm
    val effectiveTripAKm = telemetry.tripAKm ?: lastSeenState?.tripAKm
    val effectiveTripBKm = telemetry.tripBKm ?: lastSeenState?.tripBKm

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "UNFILTERED 20-BYTE TELEMETRY (0x10 SPEED+ODO • 0x11 TRIPS+CHG • 0x7D ID)",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Card: Real-Time Speedometer & Total Hardware Odometer (0x5A 0x10 Bytes 2..5)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Wheel Speed",
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SPEED (0x10 B2)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "PEAK ${telemetry.peakSpeedKmh} km/h",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.peakSpeedKmh >= 45) EnergeticCoral else NeonMint
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = telemetry.speedKmh?.toString() ?: "0",
                        style = MaterialTheme.typography.displayLarge,
                        color = if ((telemetry.speedKmh ?: 0) >= 48) EnergeticCoral else TextPrimary,
                        modifier = Modifier.testTag("live_speed_text")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "km/h",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                val accelText = telemetry.accelerationMs2?.let {
                    String.format(Locale.US, "%+.2f m/s²", it)
                } ?: "0.00 m/s²"
                Text(
                    text = "ACCEL: $accelText • ID: ${telemetry.vehicleIdString ?: IqubeBleSpec.CLUSTER_VEHICLE_ID_DEFAULT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateCardElevated)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0–30: ${telemetry.sprint0To30Sec?.let { "${it}s" } ?: "--.-s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = "0–40: ${telemetry.sprint0To40Sec?.let { "${it}s" } ?: "--.-s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonMint
                    )
                }
            }

            // Right Card: Switchable Trip A (0x11 B2..4) / Trip B (0x11 B7..9) / Total ODO (0x10 B3..5 = 35,925.2 km)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                val tabs = listOf("TRIP A", "TRIP B", "ODO", "SUB-M")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val selected = telemetry.activeTripTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) ElectricCyan.copy(alpha = 0.2f) else SlateCardElevated)
                                .border(
                                    1.dp,
                                    if (selected) ElectricCyan else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectTripTab(idx) }
                                .padding(vertical = 4.dp)
                                .testTag("trip_tab_$idx"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) ElectricCyan else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val primaryDistanceText = when (telemetry.activeTripTab) {
                    0 -> effectiveTripAKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    1 -> effectiveTripBKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    2 -> effectiveOdoKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "--.- km"
                    else -> String.format(Locale.US, "%.2f km", telemetry.integratedDistanceMeters / 1000.0)
                }

                Text(
                    text = primaryDistanceText,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    modifier = Modifier.testTag("active_trip_value_text")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "TRIP A: ${effectiveTripAKm?.let { "${it}km" } ?: "--"} • TRIP B: ${effectiveTripBKm?.let { "${it}km" } ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )

                Spacer(modifier = Modifier.height(6.dp))

                val isOdoSynced = telemetry.lastOdometerSyncMs != null &&
                    (System.currentTimeMillis() - telemetry.lastOdometerSyncMs < 2200L)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateCardElevated)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ODO (0x10 B3..5): ${effectiveOdoKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "-- km"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isOdoSynced) NeonMint else TextMuted)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "24B BE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOdoSynced) NeonMint else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifiedTripControlsCard(
    tripSubcommandConfig: TripSubcommandConfig,
    onSwitchToTripAClick: () -> Unit,
    onSwitchToTripBClick: () -> Unit,
    onResetActiveTripClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VERIFIED TRIP CONTROLS (0x5352 / HANDLE 0x0022 • 5A F1 00 … FF)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = "BYTE 19 = 0xFF",
                style = MaterialTheme.typography.labelSmall,
                color = NeonMint
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            CommandShortcutButton(
                title = "Switch to Trip A",
                subtitle = String.format(Locale.US, "5A F1 00 %02X … FF", tripSubcommandConfig.switchTripAByte),
                icon = Icons.Default.SwapHoriz,
                accentColor = NeonMint,
                testTag = "cmd_switch_trip_a_button",
                onClick = onSwitchToTripAClick,
                modifier = Modifier.weight(1f)
            )
            CommandShortcutButton(
                title = "Switch to Trip B",
                subtitle = String.format(Locale.US, "5A F1 00 %02X … FF", tripSubcommandConfig.switchTripBByte),
                icon = Icons.Default.SwapHoriz,
                accentColor = ElectricCyan,
                testTag = "cmd_switch_trip_b_button",
                onClick = onSwitchToTripBClick,
                modifier = Modifier.weight(1f)
            )
        }

        CommandShortcutButton(
            title = "Reset Active Trip (A/B)",
            subtitle = String.format(
                Locale.US,
                "5A F1 00 %02X … FF (Resets whichever trip is currently active on scooter display)",
                tripSubcommandConfig.resetActiveTripByte
            ),
            icon = Icons.Default.Refresh,
            accentColor = EnergeticCoral,
            testTag = "cmd_reset_active_trip_button",
            onClick = onResetActiveTripClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ClusterLocationSyncCard(
    detectedLocalityName: String,
    customLocationOverride: String,
    lastSyncedClusterLocation: String?,
    autoLocationSyncEnabled: Boolean,
    isCallRinging: Boolean,
    onCustomLocationOverrideChange: (String) -> Unit,
    onSetAutoLocationSync: (Boolean) -> Unit,
    onDumpLocationNowClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Cluster Location Sync",
                    tint = ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "LIVE GPS LOCATION DUMP (0x5B 0x5C + 17B ASCII + 0xFF)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Locality: \"$detectedLocalityName\" • Cluster: \"${lastSyncedClusterLocation ?: "--"}\"${if (isCallRinging) " (Paused for Call)" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Auto 10s",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = autoLocationSyncEnabled,
                    onCheckedChange = onSetAutoLocationSync
                )
            }
        }

        OutlinedTextField(
            value = customLocationOverride,
            onValueChange = { onCustomLocationOverrideChange(it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)) },
            label = {
                Text("Custom 17-Char Cluster Location Override (Bytes 2..18, Byte 19=0xFF)")
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cockpit_location_override_input")
        )

        Button(
            onClick = onDumpLocationNowClick,
            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cmd_dump_location_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Dump Current Location to Cluster",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val targetName = customLocationOverride.ifBlank { detectedLocalityName }.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
            Text(
                text = "Dump Current Location to Cluster (\"$targetName\" + 0xFF)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnByTurnNavigationInjectorCard(
    navigationState: NavigationManeuverState,
    onSendManeuver: (TurnTypeConstant, Int, Byte, String) -> Unit,
    onToggleRouteSimulation: () -> Unit
) {
    var selectedTurn by remember(navigationState.selectedTurn) { mutableStateOf(navigationState.selectedTurn) }
    var distanceInput by remember(navigationState.distanceMeters) { mutableStateOf(navigationState.distanceMeters.toString()) }
    var streetInput by remember(navigationState.streetName) { mutableStateOf(navigationState.streetName) }
    var unitIsKm by remember(navigationState.unitByte) { mutableStateOf(navigationState.unitByte == 0x02) }

    val quickManeuvers = remember {
        listOf(
            TurnTypeConstant.TURN_LEFT,
            TurnTypeConstant.TURN_RIGHT,
            TurnTypeConstant.CONTINUE_STRAIGHT,
            TurnTypeConstant.ROUNDABOUT,
            TurnTypeConstant.U_TURN,
            TurnTypeConstant.TURN_SHARPLY_RIGHT,
            TurnTypeConstant.TURN_SLIGHTLY_LEFT,
            TurnTypeConstant.DESTINATION_REACHED
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(
                1.dp,
                if (navigationState.isRouteSimulationRunning) NeonMint else SlateBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
            .testTag("navigation_injector_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = "Turn-by-Turn Navigation Injector",
                    tint = NeonMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "TURN-BY-TURN NAVIGATION INJECTOR (0x5B 0x49 → 400ms → 0x5B 0x4E)",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonMint
                    )
                    Text(
                        text = "2-Packet Sequence: Pkt1 [I (TurnCode + uint16 BE Dist + Unit) • 400ms delay • Pkt2 [NNA (15B Street)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            OutlinedButton(
                onClick = onToggleRouteSimulation,
                modifier = Modifier.testTag("toggle_nav_route_sim_button")
            ) {
                Text(
                    text = if (navigationState.isRouteSimulationRunning) "Stop Route Sim" else "Start Route Sim",
                    color = if (navigationState.isRouteSimulationRunning) EnergeticCoral else NeonMint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Quick Maneuver Chips from TurnTypeConstants.smali
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickManeuvers.forEach { maneuver ->
                val isSelected = selectedTurn == maneuver
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) NeonMint.copy(alpha = 0.22f) else SlateCardElevated)
                        .border(1.dp, if (isSelected) NeonMint else SlateBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            selectedTurn = maneuver
                            val dist = distanceInput.toIntOrNull() ?: 250
                            val unit = if (unitIsKm) IqubeBleSpec.NAV_UNIT_KILOMETERS else IqubeBleSpec.NAV_UNIT_METERS
                            onSendManeuver(maneuver, dist, unit, streetInput)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("nav_maneuver_chip_${maneuver.code}")
                ) {
                    Text(
                        text = String.format(Locale.US, "%s %s (0x%02X)", maneuver.symbol, maneuver.title, maneuver.code),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) NeonMint else TextPrimary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = distanceInput,
                onValueChange = { distanceInput = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text(if (unitIsKm) "Dist (km)" else "Dist (m)") },
                singleLine = true,
                modifier = Modifier
                    .weight(0.9f)
                    .testTag("nav_distance_input")
            )
            OutlinedButton(
                onClick = { unitIsKm = !unitIsKm },
                modifier = Modifier.testTag("nav_unit_toggle_button")
            ) {
                Text(
                    text = if (unitIsKm) "0x02 (km)" else "0x01 (m)",
                    color = ElectricCyan,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            OutlinedTextField(
                value = streetInput,
                onValueChange = { streetInput = it.take(IqubeBleSpec.MAX_NAV_STREET_ASCII_BYTES) },
                label = { Text("Street / Dest (15B)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("nav_street_input")
            )
        }

        if (!navigationState.lastPacket1Hex.isNullOrBlank() && !navigationState.lastPacket2Hex.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Pkt 1 (0x5B 0x49): ${navigationState.lastPacket1Hex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "Pkt 2 (0x5B 0x4E +400ms): ${navigationState.lastPacket2Hex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )
            }
        }

        Button(
            onClick = {
                val dist = distanceInput.toIntOrNull() ?: 250
                val unit = if (unitIsKm) IqubeBleSpec.NAV_UNIT_KILOMETERS else IqubeBleSpec.NAV_UNIT_METERS
                onSendManeuver(selectedTurn, dist, unit, streetInput)
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("send_nav_sequence_button")
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send 2-Packet Nav", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "Send 2-Packet Nav: %s (0x%02X) • %s%s • \"%s\"",
                    selectedTurn.title,
                    selectedTurn.code,
                    distanceInput.ifBlank { "0" },
                    if (unitIsKm) "km" else "m",
                    streetInput.take(15)
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun IncomingCallClusterRelayCard(
    incomingCallState: IncomingCallRelayState,
    detectedLocalityName: String,
    onTriggerCall: (String) -> Unit,
    onEndCall: () -> Unit
) {
    var callerInput by remember { mutableStateOf(incomingCallState.fullCallerName) }
    val extractedFirst = remember(callerInput) {
        callerInput.trim().split(Regex("\\s+")).firstOrNull()?.replace(Regex("[^\\x20-\\x7E]"), "")?.ifBlank { "Caller" } ?: "Caller"
    }
    val previewClusterText = remember(extractedFirst) {
        extractedFirst.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(
                1.dp,
                if (incomingCallState.isRinging) AmberWarning else SlateBorder,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
            .testTag("incoming_call_relay_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Dedicated Caller ID Banner Relay",
                    tint = AmberWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "DEDICATED CALLER ID BANNER (0x5B 0x43 + 500ms REPEAT TIMER)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberWarning
                    )
                    Text(
                        text = "Sends [0x5B, 0x43, \"$previewClusterText\", 0xFF] + CallState 0x00 (Ringing) every 500ms • Auto-restores \"$detectedLocalityName\" on end",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        OutlinedTextField(
            value = callerInput,
            onValueChange = { callerInput = it },
            label = { Text("Caller Full Name (Extracts 1st Name: '$extractedFirst' → 0x5B 0x43 '$previewClusterText')") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("caller_name_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onTriggerCall(callerInput) },
                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                modifier = Modifier
                    .weight(1f)
                    .testTag("trigger_call_alert_button")
            ) {
                Icon(Icons.Default.Call, contentDescription = "Ring Cluster", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ring 0x5B 0x43 (\"$previewClusterText\")",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (incomingCallState.isRinging) {
                Button(
                    onClick = onEndCall,
                    colors = ButtonDefaults.buttonColors(containerColor = EnergeticCoral, contentColor = SlateSurface),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("end_call_alert_button")
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End Call (0x02 & Restore GPS)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandShortcutButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(SlateCardElevated)
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ClusterMusicAndRiderGreetingCard(
    riderGreetingName: String,
    musicTrackTitle: String,
    onSendMusicTrackClick: (String) -> Unit,
    onSyncRiderGreetingClick: (String) -> Unit,
    onSendPhoneStatusRelayClick: () -> Unit
) {
    var riderInput by remember(riderGreetingName) { mutableStateOf(riderGreetingName) }
    var songInput by remember(musicTrackTitle) { mutableStateOf(musicTrackTitle) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("music_rider_greeting_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RIDER GREETING (0x5B 0x52) • MUSIC TRACK (0x5B 0x62) • PHONE STATUS (0x5B 0x52 0x55)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
                Text(
                    text = "Greeting: \"Hello ${riderInput.ifBlank { "Rider" }}\" • Track: \"${songInput.ifBlank { "Midnight City" }}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            OutlinedButton(
                onClick = onSendPhoneStatusRelayClick,
                modifier = Modifier.testTag("sync_phone_status_button")
            ) {
                Text("Sync Phone [RU", color = NeonMint, style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = riderInput,
                onValueChange = { riderInput = it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES) },
                label = { Text("Rider Name (0x5B 0x52 'Hello <Name>')") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("rider_greeting_input")
            )
            Button(
                onClick = { onSyncRiderGreetingClick(riderInput) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                modifier = Modifier.testTag("sync_rider_greeting_button")
            ) {
                Text("Sync Name", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = songInput,
                onValueChange = { songInput = it.take(IqubeBleSpec.MAX_LOCATION_ASCII_BYTES) },
                label = { Text("Music Track Title (SONG_NAME_PACKET 0x5B 0x62)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("music_track_input")
            )
            Button(
                onClick = { onSendMusicTrackClick(songInput) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                modifier = Modifier.testTag("send_music_track_button")
            ) {
                Text("Push Song", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

