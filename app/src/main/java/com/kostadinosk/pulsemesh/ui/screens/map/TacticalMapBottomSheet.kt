// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.ui.screens.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.ui.theme.LocalPulseTokens
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.AppStrings
import com.kostadinosk.pulsemesh.util.ThemeBrightness
import com.kostadinosk.pulsemesh.util.ThemePreset
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState

/**
 * PulseMesh v2.0 Edge-to-Edge Map 2.0 Overlay & 3-State Draggable/Expandable Sheet:
 * - State 0 (Collapsed): compact peer count & GPS status handle
 * - State 1 (Half): live peers list + waypoints quick jump
 * - State 2 (Expanded): offline map packs (.mbtiles/.pmtiles), GPX/KML import & export, ATAK CoT bridge toggle
 */
@Composable
fun TacticalMapOverlaySheet(
    state: MeshUiState,
    onSelectPeer: (MeshNode) -> Unit,
    onImportMapPack: () -> Unit,
    onImportGpxKml: () -> Unit,
    onExportGpx: () -> Unit,
    onToggleCotBridge: (Boolean) -> Unit,
    onBroadcastGpsPin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalPulseTokens.current
    val p = tokens.palette
    val lang = state.appLanguage

    // 0 = Collapsed, 1 = Half (Peers & Waypoints), 2 = Expanded (Packs, GPX, CoT)
    var sheetLevel by remember { mutableIntStateOf(0) }
    val peers = remember(state.nodes) { state.nodes.filter { !it.isSelf } }

    Box(modifier = modifier.fillMaxSize()) {
        // Minimal Floating Top Coordinate Pill
        Surface(
            color = p.surface.copy(alpha = 0.92f),
            shape = tokens.pillShape,
            border = BorderStroke(tokens.borderWidth, p.border),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp, start = 14.dp, end = 14.dp)
                .testTag("map_top_coord_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.isGpsLocked) tokens.statusOnline else tokens.statusWarning)
                )
                Text(
                    text = if (state.userLatitude != null && state.userLongitude != null && (state.userLatitude != 0.0 || state.userLongitude != 0.0)) {
                        "%.4f, %.4f".format(state.userLatitude, state.userLongitude)
                    } else {
                        AppStrings.tr(lang, "Acquiring GPS...", "Αναζήτηση GPS...")
                    },
                    color = p.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
                if (state.isCotBridgeEnabled) {
                    Surface(
                        color = tokens.statusOnline.copy(alpha = 0.18f),
                        shape = tokens.pillShape
                    ) {
                        Text(
                            text = "ATAK CoT",
                            color = tokens.statusOnline,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Minimal Right-Side FAB Stack (Layers / Pin / My Location)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(
                onClick = { sheetLevel = 2 },
                containerColor = p.surface,
                contentColor = tokens.accentSecondary
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Map Layers & Packs", modifier = Modifier.size(18.dp))
            }
            SmallFloatingActionButton(
                onClick = onBroadcastGpsPin,
                containerColor = p.surface,
                contentColor = tokens.accentPrimary
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Broadcast GPS Pin", modifier = Modifier.size(18.dp))
            }
            FloatingActionButton(
                onClick = { sheetLevel = if (sheetLevel == 0) 1 else 0 },
                containerColor = tokens.accentPrimary,
                contentColor = if (tokens.isDark) Color.Black else Color.White,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = "Field Sheet", modifier = Modifier.size(20.dp))
            }
        }

        // 3-State Bottom Sheet anchored to bottom of Map
        Surface(
            color = p.surface.copy(alpha = 0.96f),
            shape = tokens.sheetShape,
            border = BorderStroke(tokens.borderWidth, p.border),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .testTag("map_3state_bottom_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Drag / Tap Handle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sheetLevel = (sheetLevel + 1) % 3 }
                        .testTag("map_sheet_toggle_handle"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(tokens.pillShape)
                                .background(p.borderBright)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${peers.size} ${AppStrings.tr(lang, "Peers", "Κόμβοι")} • ${state.waypoints.size} ${AppStrings.tr(lang, "Waypoints", "Σημεία")}",
                            color = p.textPrimary,
                            fontFamily = tokens.labelFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = when (sheetLevel) {
                                0 -> AppStrings.tr(lang, "Expand", "Ανάπτυξη")
                                1 -> AppStrings.tr(lang, "Tools", "Εργαλεία")
                                else -> AppStrings.tr(lang, "Collapse", "Σύμπτυξη")
                            },
                            color = tokens.accentPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (sheetLevel == 2) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = "Toggle Sheet",
                            tint = tokens.accentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // State 1+: Peers & Waypoints List
                AnimatedVisibility(visible = sheetLevel >= 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (peers.isEmpty()) {
                            Text(
                                text = AppStrings.tr(lang, "No remote peers on radar yet. Tap My Location FAB to broadcast a GPS ping.", "Δεν υπάρχουν κόμβοι στο ραντάρ ακόμη."),
                                color = p.textSecondary,
                                fontSize = 11.sp
                            )
                        } else {
                            peers.take(4).forEach { peer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(tokens.buttonShape)
                                        .background(p.surfaceVariant)
                                        .clickable { onSelectPeer(peer) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(peer.callsign, color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("${peer.rssiDbm} dBm • ${peer.hopCount}h", color = tokens.statusOnline, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // State 2: Offline Map Packs, GPX/KML & ATAK CoT
                AnimatedVisibility(visible = sheetLevel == 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onImportMapPack,
                                shape = tokens.buttonShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(".mbtiles/.pmtiles", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onImportGpxKml,
                                shape = tokens.buttonShape,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Import GPX/KML", fontSize = 11.sp)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Radar, contentDescription = null, tint = tokens.statusOnline, modifier = Modifier.size(18.dp))
                                Column {
                                    Text("ATAK / WinTAK CoT Bridge", color = p.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("Multicast UDP 239.2.3.1:6969", color = p.textSecondary, fontSize = 10.sp)
                                }
                            }
                            Switch(checked = state.isCotBridgeEnabled, onCheckedChange = onToggleCotBridge)
                        }
                        Button(
                            onClick = onExportGpx,
                            shape = tokens.buttonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.accentPrimary,
                                contentColor = if (tokens.isDark) Color.Black else Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(AppStrings.tr(lang, "Export Breadcrumbs & Waypoints (GPX)", "Εξαγωγή Διαδρομής (GPX)"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Map 2.0 Edge-to-Edge Overlay Sheet", showBackground = true)
@Composable
fun PreviewMapOverlaySheet() {
    PulseMeshTheme(themePreset = ThemePreset.PRACTICAL_TACTICAL, themeBrightness = ThemeBrightness.DARK) {
        Surface(color = LocalPulseTokens.current.palette.background, modifier = Modifier.height(460.dp)) {
            TacticalMapOverlaySheet(
                state = MeshUiState(userLatitude = 37.9838, userLongitude = 23.7275, isGpsLocked = true, isCotBridgeEnabled = true),
                onSelectPeer = {},
                onImportMapPack = {},
                onImportGpxKml = {},
                onExportGpx = {},
                onToggleCotBridge = {},
                onBroadcastGpsPin = {}
            )
        }
    }
}
