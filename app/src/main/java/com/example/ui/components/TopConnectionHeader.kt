package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BleConnectionPhase
import com.example.model.RideMode
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopConnectionHeader(
    connectionPhase: BleConnectionPhase,
    deviceName: String,
    deviceMac: String,
    rssiDbm: Int?,
    negotiatedMtu: Int?,
    isIgnitionUnlocked: Boolean?,
    rideMode: RideMode?,
    isLinkDegraded: Boolean,
    linkWarningMessage: String?,
    onConnectBleClick: () -> Unit,
    onReplayHciClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = connectionPhase == BleConnectionPhase.STREAMING_ACTIVE ||
        connectionPhase == BleConnectionPhase.REPLAYING_HCI_LOG

    val statusDotColor = when {
        isLinkDegraded -> EnergeticCoral
        connectionPhase == BleConnectionPhase.STREAMING_ACTIVE -> NeonMint
        connectionPhase == BleConnectionPhase.REPLAYING_HCI_LOG -> ElectricCyan
        connectionPhase == BleConnectionPhase.DISCONNECTED -> TextSecondary
        else -> AmberWarning
    }

    val statusLabel = when (connectionPhase) {
        BleConnectionPhase.DISCONNECTED -> "BLE DISCONNECTED"
        BleConnectionPhase.SCANNING -> "SCANNING 0x5354…"
        BleConnectionPhase.CONNECTING_GATT -> "CONNECTING GATT…"
        BleConnectionPhase.DISCOVERING_SERVICES -> "DISCOVERING SERVICES…"
        BleConnectionPhase.ENABLING_CCCD -> "WRITING CCCD 0x001F…"
        BleConnectionPhase.NEGOTIATING_MTU -> "EXCHANGING MTU 517…"
        BleConnectionPhase.SENDING_HANDSHAKE -> "HANDSHAKE [RU 0x0022…"
        BleConnectionPhase.STREAMING_ACTIVE -> "BLE CONNECTED"
        BleConnectionPhase.REPLAYING_HCI_LOG -> "HCI LOG REPLAY (LIVE PARSER)"
        BleConnectionPhase.RECONNECTING_BACKOFF -> "AUTO-RECONNECTING…"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateSurface)
            .border(width = 1.dp, color = SlateBorder, shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Scooter Identity & MAC
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusDotColor.copy(alpha = 0.14f))
                        .border(1.dp, statusDotColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isActive -> Icons.Default.BluetoothConnected
                            connectionPhase == BleConnectionPhase.DISCONNECTED -> Icons.Default.BluetoothDisabled
                            else -> Icons.Default.Bluetooth
                        },
                        contentDescription = "BLE Connection Status",
                        tint = statusDotColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Ignition / Lock status pill derived strictly from 0x7D telemetry byte
                        val lockIcon = if (isIgnitionUnlocked == true) Icons.Default.LockOpen else Icons.Default.Lock
                        val lockTint = if (isIgnitionUnlocked == true) NeonMint else TextSecondary
                        Icon(
                            imageVector = lockIcon,
                            contentDescription = if (isIgnitionUnlocked == true) "Ignition Unlocked" else "Ignition Locked",
                            tint = lockTint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "$deviceMac • ${rssiDbm?.let { "$it dBm" } ?: "-- dBm"} • MTU ${negotiatedMtu ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Right: Connection Status Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, statusDotColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusDotColor
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Bar: Scan & Connect BLE, Replay Captured btsnooz_hci.log, Ride Mode badge
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (connectionPhase == BleConnectionPhase.DISCONNECTED ||
                connectionPhase == BleConnectionPhase.RECONNECTING_BACKOFF
            ) {
                HeaderActionButton(
                    label = "Scan BLE (0x5354)",
                    icon = Icons.Default.Bluetooth,
                    accentColor = NeonMint,
                    testTag = "connect_ble_button",
                    onClick = onConnectBleClick
                )
                HeaderActionButton(
                    label = "Replay btsnooz_hci.log",
                    icon = Icons.Default.PlayArrow,
                    accentColor = ElectricCyan,
                    testTag = "replay_hci_button",
                    onClick = onReplayHciClick
                )
            } else {
                HeaderActionButton(
                    label = if (connectionPhase == BleConnectionPhase.REPLAYING_HCI_LOG) "Stop HCI Replay" else "Disconnect BLE",
                    icon = Icons.Default.Stop,
                    accentColor = EnergeticCoral,
                    testTag = "disconnect_ble_button",
                    onClick = onDisconnectClick
                )
                if (connectionPhase != BleConnectionPhase.REPLAYING_HCI_LOG) {
                    HeaderActionButton(
                        label = "Replay btsnooz_hci.log",
                        icon = Icons.Default.PlayArrow,
                        accentColor = ElectricCyan,
                        testTag = "switch_to_replay_button",
                        onClick = onReplayHciClick
                    )
                }
            }

            // Live Ride Mode Pill (from 0x5A 0x7D)
            val modeText = rideMode?.label ?: "MODE: --"
            val modeColor = when (rideMode) {
                RideMode.POWER -> EnergeticCoral
                RideMode.ECO -> NeonMint
                RideMode.PARK -> AmberWarning
                null -> TextSecondary
            }
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateCardElevated)
                    .border(1.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = modeColor
                )
            }
        }

        // Link Watchdog / Supervision Timeout (0x08) Warning Banner
        AnimatedVisibility(visible = linkWarningMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (isLinkDegraded) EnergeticCoral.copy(alpha = 0.16f) else AmberWarning.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isLinkDegraded) EnergeticCoral.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Link Warning",
                        tint = if (isLinkDegraded) EnergeticCoral else AmberWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = linkWarningMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
    }
}
