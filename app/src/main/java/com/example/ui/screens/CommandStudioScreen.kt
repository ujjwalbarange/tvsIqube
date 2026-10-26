package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ClusterAlertType
import com.example.model.NavigationTurnIcon
import com.example.model.QueuedWriteCommand
import com.example.model.WriteCommandStatus
import com.example.model.toAsciiPreview
import com.example.model.toHexString
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EnergeticCoral
import com.example.ui.theme.NeonMint
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCardElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandStudioScreen(
    commandHistory: List<QueuedWriteCommand>,
    queueDepth: Int,
    heartbeatEnabled: Boolean,
    onSetHeartbeatEnabled: (Boolean) -> Unit,
    onSendNavigation: (NavigationTurnIcon, Int, String, Boolean) -> Unit,
    onSendCallerAlert: (ClusterAlertType, String) -> Unit,
    onSendCustomHex: (String, String) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var selectedTurn by remember { mutableStateOf(NavigationTurnIcon.TURN_RIGHT) }
    var navDistanceMeters by remember { mutableFloatStateOf(250f) }
    var navRoadName by remember { mutableStateOf("100 FT INDIRANAGAR") }
    var use5ANavEnvelope by remember { mutableStateOf(false) }

    var selectedAlertType by remember { mutableStateOf(ClusterAlertType.INCOMING_CALL) }
    var callerBannerText by remember { mutableStateOf("PRIYA SHARMA") }

    var customHexInput by remember {
        mutableStateOf("5A F1 00 01 01 00 00 00 00 00 00 00 00 00 00 00 00 00 A8 FF")
    }
    var customHexError by remember { mutableStateOf<String?>(null) }

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
            // 1. Turn-by-Turn Navigation Feeder (5B 5C 57 / [\W or 5A 57 .. FF)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TURN-BY-TURN NAVIGATION FEEDER (0x5352 / 0x0022)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (use5ANavEnvelope) "5A..FF Frame" else "[\\W (5B 5C 57)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = use5ANavEnvelope,
                            onCheckedChange = { use5ANavEnvelope = it }
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavigationTurnIcon.entries.forEach { icon ->
                        val isSelected = selectedTurn == icon
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) ElectricCyan.copy(alpha = 0.2f) else SlateCardElevated)
                                .border(
                                    1.dp,
                                    if (isSelected) ElectricCyan else SlateBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedTurn = icon }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${icon.symbol} ${icon.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) ElectricCyan else TextPrimary
                            )
                        }
                    }
                }

                Text(
                    text = "Distance to Turn: ${navDistanceMeters.roundToInt()} meters",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Slider(
                    value = navDistanceMeters,
                    onValueChange = { navDistanceMeters = it },
                    valueRange = 10f..2000f
                )

                OutlinedTextField(
                    value = navRoadName,
                    onValueChange = { navRoadName = it.take(15) },
                    label = { Text("Cluster Road Name (ASCII max 13–14 chars)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_road_name_input")
                )

                Button(
                    onClick = {
                        onSendNavigation(
                            selectedTurn,
                            navDistanceMeters.roundToInt(),
                            navRoadName,
                            use5ANavEnvelope
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dispatch_nav_frame_button")
                ) {
                    Text(
                        text = "Dispatch 20-Byte Navigation Frame to 0x5352",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Caller ID / SMS / Cluster Alert Banner (5B 5D 43 / []C)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "CALLER ID & CLUSTER ALERT BANNER (5B 5D 43 / []C)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClusterAlertType.entries.forEach { alert ->
                        val selected = selectedAlertType == alert
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) NeonMint.copy(alpha = 0.2f) else SlateCardElevated)
                                .border(
                                    1.dp,
                                    if (selected) NeonMint else SlateBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedAlertType = alert }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = alert.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) NeonMint else TextPrimary
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = callerBannerText,
                    onValueChange = { callerBannerText = it.take(15) },
                    label = { Text("Caller / Alert ASCII Banner (15 bytes max)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("caller_banner_input")
                )

                Button(
                    onClick = { onSendCallerAlert(selectedAlertType, callerBannerText) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMint, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dispatch_alert_frame_button")
                ) {
                    Text(
                        text = "Push Alert Banner Frame (5B 5D 43 … FF)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Guarded 20-Byte Hex Command Builder & Protocol Validator
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "GUARDED 20-BYTE HEX INJECTOR (PROTOCOL SAFETY CHECK)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberWarning
                )
                Text(
                    text = "Enforces 20-byte length and valid 0x5A…0xFF or 0x5B ([RU, [JD, [\\W, []C) framing before GATT write.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = customHexInput,
                    onValueChange = {
                        customHexInput = it
                        customHexError = null
                    },
                    label = { Text("20-Byte Hex Payload (Space-separated)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_hex_input")
                )

                if (customHexError != null) {
                    Text(
                        text = customHexError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EnergeticCoral
                    )
                }

                Button(
                    onClick = {
                        val res = onSendCustomHex("Custom 20B Hex Frame", customHexInput)
                        customHexError = res.exceptionOrNull()?.message
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = SlateSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_custom_hex_button")
                ) {
                    Text(
                        text = "Validate & Enqueue 20-Byte Frame",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 4. Serialized FIFO Write Queue Monitor & Heartbeat Toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SERIALIZED FIFO WRITE QUEUE (HANDLE 0x0022)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Pending Queue Depth: $queueDepth • 500ms ATT Write Response (0x13) Guard",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[JD 1.8s Sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = heartbeatEnabled,
                            onCheckedChange = onSetHeartbeatEnabled
                        )
                    }
                }

                if (commandHistory.isEmpty()) {
                    Text(
                        text = "No outgoing commands dispatched yet. Trigger an action above or start HCI Replay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    commandHistory.take(12).forEach { cmd ->
                        val badgeColor = when (cmd.status) {
                            WriteCommandStatus.ACK_RECEIVED -> NeonMint
                            WriteCommandStatus.AWAITING_ACK, WriteCommandStatus.QUEUED -> AmberWarning
                            WriteCommandStatus.TIMEOUT_500MS,
                            WriteCommandStatus.REJECTED_FRAMING,
                            WriteCommandStatus.DISCONNECTED_ERROR -> EnergeticCoral
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SlateCardElevated)
                                .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${cmd.id} ${cmd.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${cmd.status.name}${cmd.ackLatencyMs?.let { " (${it}ms)" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "HEX: ${cmd.payload.toHexString()}  |  ASCII: ${cmd.payload.toAsciiPreview()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            if (cmd.errorDetail != null) {
                                Text(
                                    text = cmd.errorDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EnergeticCoral
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
