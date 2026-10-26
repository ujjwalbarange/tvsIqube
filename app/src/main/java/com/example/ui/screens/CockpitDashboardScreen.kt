package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ClusterAlertType
import com.example.model.NavigationTurnIcon
import com.example.model.TelemetryState
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
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitDashboardScreen(
    telemetry: TelemetryState,
    onSelectTripTab: (Int) -> Unit,
    onResetTripClick: () -> Unit,
    onCycleTripClick: () -> Unit,
    onPushSampleNavClick: (NavigationTurnIcon, Int, String) -> Unit,
    onSendSampleCallAlertClick: (ClusterAlertType, String) -> Unit,
    onToggleLampClick: () -> Unit,
    onToggleChargingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

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
            // 1. HERO VISUALIZER: Circular Battery SoC Arc Gauge & Range Card (Opcode 0x13)
            HeroBatterySocGaugeCard(
                telemetry = telemetry,
                onToggleChargingClick = onToggleChargingClick
            )

            // 2. LIVE TELEMETRY STRIP: Speedometer (0x10), Sprint Timer & Tabbed Trip/Odometer (0x11)
            LiveTelemetryStripSection(
                telemetry = telemetry,
                onSelectTripTab = onSelectTripTab
            )

            // 3. INTERACTIVE COMMAND DECK (Write to 0x5352 / Handle 0x0022)
            InteractiveCommandDeckCard(
                isLampOn = telemetry.isDashboardLampOn,
                onResetTripClick = onResetTripClick,
                onCycleTripClick = onCycleTripClick,
                onPushSampleNavClick = {
                    onPushSampleNavClick(NavigationTurnIcon.TURN_RIGHT, 180, "MG ROAD")
                },
                onSendSampleCallAlertClick = {
                    onSendSampleCallAlertClick(ClusterAlertType.INCOMING_CALL, "ARJUN +9198")
                },
                onToggleLampClick = onToggleLampClick
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HeroBatterySocGaugeCard(
    telemetry: TelemetryState,
    onToggleChargingClick: () -> Unit
) {
    val soc = telemetry.batteryPercent
    val targetSweep = if (soc != null) (soc / 100f) * 260f else 0f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "soc_arc_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "charging_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
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
            .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BATTERY STATE OF CHARGE (0x5A 0x13)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (telemetry.isCharging) NeonMint.copy(alpha = 0.16f) else SlateSurface)
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
                        "CHARGING • ${telemetry.timeToFullMinutes ?: "--"} MIN"
                    } else {
                        "DISCHARGING"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (telemetry.isCharging) NeonMint else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Circular Arc Gauge + Speed Overlay
        Box(
            modifier = Modifier.size(210.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 16.dp.toPx()
                val inset = strokeWidth / 2f + 8.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)

                // Background Track Arc (140 deg to +260 deg)
                drawArc(
                    color = SlateBorder,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active SoC Gradient Arc
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
                    text = soc?.let { "$it %" } ?: "-- %",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (soc != null) TextPrimary else TextMuted,
                    modifier = Modifier.testTag("battery_soc_text")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = telemetry.rangeEcoKm?.let { "EST. RANGE: $it KM" } ?: "EST. RANGE: -- KM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan,
                    modifier = Modifier.testTag("estimated_range_text")
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = telemetry.rangePowerKm?.let { "POWER MODE: ~$it KM" } ?: "POWER MODE: -- KM",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Pack Voltage, Efficiency & Regen Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniMetricPill(
                label = "PACK VOLTS",
                value = telemetry.packVoltageVolts?.let { String.format(Locale.US, "%.1f V", it) } ?: "--.- V",
                accent = ElectricCyan,
                modifier = Modifier.weight(1f)
            )
            MiniMetricPill(
                label = "EFFICIENCY",
                value = telemetry.efficiencyWhPerKm?.let { "$it Wh/km" } ?: "-- Wh/km",
                accent = NeonMint,
                modifier = Modifier.weight(1f)
            )
            MiniMetricPill(
                label = "REGEN BRAKE",
                value = if (telemetry.isRegenActive) "ACTIVE" else "IDLE",
                accent = if (telemetry.isRegenActive) NeonMint else TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiniMetricPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

@Composable
private fun LiveTelemetryStripSection(
    telemetry: TelemetryState,
    onSelectTripTab: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "LIVE TELEMETRY STREAM (PACKETS 0x10 / 0x11 / 0x7D)",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Card: Real-Time Speedometer, Peak Speed & Acceleration (Opcode 0x10)
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
                            text = "WHEEL SPEED",
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
                        text = telemetry.speedKmh?.toString() ?: "--",
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
                } ?: "--.-- m/s²"
                Text(
                    text = "ACCEL: $accelText • THR: ${telemetry.throttlePercent?.let { "$it%" } ?: "--%"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 0-30 & 0-40 km/h Automatic Drag Timer
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

            // Right Card: Switchable Trip A / Trip B / ODO / Sub-Meter Integrated Card (Opcode 0x11 & 0x10)
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
                    0 -> telemetry.tripAKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    1 -> telemetry.tripBKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "--.- km"
                    2 -> telemetry.odometerKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "--.- km"
                    else -> String.format(Locale.US, "%.1f m", telemetry.integratedDistanceMeters)
                }

                Text(
                    text = primaryDistanceText,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    modifier = Modifier.testTag("active_trip_value_text")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "INTEGRATED: ${String.format(Locale.US, "%.1f m", telemetry.integratedDistanceMeters)} (${String.format(Locale.US, "%.1f km", telemetry.clusterIntegratedStepKm)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonMint
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Hardware Odometer + 0x11 2.02s Sync Pulse Indicator
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
                        text = "ODO: ${telemetry.odometerKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: "-- km"}",
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
                            text = "0x11",
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
private fun InteractiveCommandDeckCard(
    isLampOn: Boolean,
    onResetTripClick: () -> Unit,
    onCycleTripClick: () -> Unit,
    onPushSampleNavClick: () -> Unit,
    onSendSampleCallAlertClick: () -> Unit,
    onToggleLampClick: () -> Unit
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
                text = "ACTION SHORTCUTS (FIFO WRITE TO 0x5352 / 0x0022)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = "5A..FF GUARDED",
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
                title = "Reset Trip A",
                subtitle = "5A F1 00 02 … FF",
                icon = Icons.Default.Refresh,
                accentColor = EnergeticCoral,
                testTag = "cmd_reset_trip_button",
                onClick = onResetTripClick,
                modifier = Modifier.weight(1f)
            )
            CommandShortcutButton(
                title = "Cycle Trip A/B",
                subtitle = "5A F1 00 01 … FF",
                icon = Icons.Default.SwapHoriz,
                accentColor = ElectricCyan,
                testTag = "cmd_cycle_trip_button",
                onClick = onCycleTripClick,
                modifier = Modifier.weight(1f)
            )
            CommandShortcutButton(
                title = "Push Route Nav",
                subtitle = "5B 5C 57 ([\\W) 180m",
                icon = Icons.Default.Navigation,
                accentColor = NeonMint,
                testTag = "cmd_push_nav_button",
                onClick = onPushSampleNavClick,
                modifier = Modifier.weight(1f)
            )
            CommandShortcutButton(
                title = "Send Call Alert",
                subtitle = "5B 5D 43 ([]C) Banner",
                icon = Icons.Default.NotificationsActive,
                accentColor = AmberWarning,
                testTag = "cmd_send_alert_button",
                onClick = onSendSampleCallAlertClick,
                modifier = Modifier.weight(1f)
            )
        }

        CommandShortcutButton(
            title = if (isLampOn) "Dashboard Cluster Lamp: ON (Tap to Toggle)" else "Dashboard Cluster Lamp: OFF (Tap to Toggle)",
            subtitle = "5A F1 00 03 … FF (20-Byte Framed Control)",
            icon = Icons.Default.LightMode,
            accentColor = if (isLampOn) NeonMint else ElectricCyan,
            testTag = "cmd_toggle_lamp_button",
            onClick = onToggleLampClick,
            modifier = Modifier.fillMaxWidth()
        )
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
