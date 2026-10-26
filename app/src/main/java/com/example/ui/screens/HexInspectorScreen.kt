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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ByteOffsetMapping
import com.example.model.TelemetryState
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HexInspectorScreen(
    telemetry: TelemetryState,
    byteOffsetMapping: ByteOffsetMapping,
    fragmentedSliceTestEnabled: Boolean,
    onUpdateMapping: (ByteOffsetMapping) -> Unit,
    onSetFragmentedSliceTest: (Boolean) -> Unit,
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
            // 1. Live 20-Byte Hex & ASCII Stream Monitor (Handle 0x001E / 0x5354)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIVE 20-BYTE BINARY STREAM (HANDLE 0x001E / 0x5354)",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Malformed / Resynced Chunks: ${telemetry.malformedPacketCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "8B+12B Slice Test",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Switch(
                            checked = fragmentedSliceTestEnabled,
                            onCheckedChange = onSetFragmentedSliceTest,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x10 • Wheel Speed & Throttle (~4.7 Hz)",
                    packetCount = telemetry.packetCount0x10,
                    frame = telemetry.latestFrame0x10,
                    highlightByteIndex = 2,
                    accentColor = ElectricCyan
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x13 • Battery SoC & Range (~3.1 Hz)",
                    packetCount = telemetry.packetCount0x13,
                    frame = telemetry.latestFrame0x13,
                    highlightByteIndex = byteOffsetMapping.rangeByteOffset,
                    accentColor = NeonMint
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x7D • Cluster Status & Bitfields (~3.1 Hz)",
                    packetCount = telemetry.packetCount0x7D,
                    frame = telemetry.latestFrame0x7D,
                    highlightByteIndex = byteOffsetMapping.modeFlagByteOffset,
                    accentColor = AmberWarning
                )
                HexFrameInspectorRow(
                    opcodeTitle = "0x5A 0x11 • Hardware Odometer & Trips (0.5 Hz / 2.02s)",
                    packetCount = telemetry.packetCount0x11,
                    frame = telemetry.latestFrame0x11,
                    highlightByteIndex = byteOffsetMapping.odometerByteOffset,
                    accentColor = EnergeticCoral
                )
            }

            // 2. Interactive Byte-Offset Mapper (Bytes 3..19)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "DYNAMIC BYTE-OFFSET MAPPER (BYTES 3..18)",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonMint
                )
                Text(
                    text = "Tap a byte index to dynamically remap which offset in incoming 20-byte frames feeds Range, Trip A, Trip B, and Odometer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                ByteIndexSelectorRow(
                    label = "0x13 Projected Range (km) Byte Offset (Default Byte 3 = 0x60 / 96 km)",
                    selectedIndex = byteOffsetMapping.rangeByteOffset,
                    validIndices = 3..18,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(rangeByteOffset = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x11 Odometer (32-bit LE, 0.1 km) Start Byte Offset (Default Byte 3)",
                    selectedIndex = byteOffsetMapping.odometerByteOffset,
                    validIndices = 3..14,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(odometerByteOffset = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x11 Trip A (16-bit LE, 0.1 km) Start Byte Offset (Default Byte 7)",
                    selectedIndex = byteOffsetMapping.tripAByteOffset,
                    validIndices = 3..16,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(tripAByteOffset = it)) }
                )

                ByteIndexSelectorRow(
                    label = "0x7D Ride Mode & Regen Bitfield Byte Offset (Default Byte 3)",
                    selectedIndex = byteOffsetMapping.modeFlagByteOffset,
                    validIndices = 3..18,
                    onSelect = { onUpdateMapping(byteOffsetMapping.copy(modeFlagByteOffset = it)) }
                )
            }

            // 3. 0x5A 0x7D Cluster Bitfield Inspector
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "0x5A 0x7D CLUSTER STATUS BITFIELD INSPECTOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberWarning
                )
                val frame7D = telemetry.latestFrame0x7D
                val flagByte = if (frame7D != null && byteOffsetMapping.modeFlagByteOffset < frame7D.size) {
                    frame7D[byteOffsetMapping.modeFlagByteOffset].toInt() and 0xFF
                } else null

                Text(
                    text = "Identity Byte 2: ${telemetry.clusterIdentityByte?.let { "0x${Integer.toHexString(it).uppercase()} ($it dec)" } ?: "--"}  •  Flag Byte [${byteOffsetMapping.modeFlagByteOffset}]: ${flagByte?.let { "0b" + it.toString(2).padStart(8, '0') } ?: "--"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val bitLabels = listOf(
                        "Bit 0: Power Mode",
                        "Bit 1: Regen Active",
                        "Bit 2: Cluster Lamp",
                        "Bit 3: Park Standby",
                        "Bit 4: Ignition Lock",
                        "Bit 5: Left Turn",
                        "Bit 6: Right Turn",
                        "Bit 7: Hazard Alert"
                    )
                    bitLabels.forEachIndexed { bitIdx, name ->
                        val isHigh = flagByte != null && ((flagByte shr bitIdx) and 1) == 1
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isHigh) NeonMint.copy(alpha = 0.2f) else SlateCardElevated)
                                .border(
                                    1.dp,
                                    if (isHigh) NeonMint else SlateBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "$name = ${if (isHigh) "1" else "0"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHigh) NeonMint else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HexFrameInspectorRow(
    opcodeTitle: String,
    packetCount: Int,
    frame: ByteArray?,
    highlightByteIndex: Int,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateCardElevated)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = opcodeTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = "$packetCount pkts",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (frame == null) {
            Text(
                text = "-- NO PACKET RECEIVED YET (AWAITING 0x5354 STREAM) --",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        } else {
            Text(
                text = "HEX: ${frame.toHexString()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
            Text(
                text = "ASCII: ${frame.toAsciiPreview()}   |   Byte[$highlightByteIndex] = 0x${String.format("%02X", frame[highlightByteIndex].toInt() and 0xFF)} (${frame[highlightByteIndex].toInt() and 0xFF})",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByteIndexSelectorRow(
    label: String,
    selectedIndex: Int,
    validIndices: IntRange,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (idx in validIndices) {
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) ElectricCyan.copy(alpha = 0.22f) else SlateCardElevated)
                        .border(
                            1.dp,
                            if (selected) ElectricCyan else SlateBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(idx) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("byte_offset_${label.take(4)}_$idx")
                ) {
                    Text(
                        text = "B$idx",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) ElectricCyan else TextSecondary
                    )
                }
            }
        }
    }
}
