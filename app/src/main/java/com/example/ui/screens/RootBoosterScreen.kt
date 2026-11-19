// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeviceBrand
import com.example.model.DeviceCatalog
import com.example.model.PhoneModelProfile
import com.example.ui.components.AeroGlossBadgeIcon
import com.example.ui.components.HardwareMismatchWarningBanner
import com.example.ui.components.LiveBleDiagnosticPanel
import com.example.ui.theme.*
import com.example.viewmodel.MeshUiState

@Composable
fun RootBoosterScreen(
    state: MeshUiState,
    onToggleBleCoded: () -> Unit,
    onToggleWifiStuffing: () -> Unit,
    onToggleUnthrottledScan: () -> Unit,
    onToggleSystemlessMount: () -> Unit,
    onSelectPhoneProfile: (PhoneModelProfile) -> Unit,
    onAutoSwitchToDetected: () -> Unit,
    onForceOverrideMismatch: () -> Unit,
    onOpenMismatchDialog: () -> Unit,
    onVerifyRoot: () -> Unit,
    onStartBleTx: () -> Unit = {},
    onStopBleTx: () -> Unit = {},
    onToggleRxSniffer: () -> Unit = {},
    onClearBleDevices: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onForceGattReDiscovery: () -> Unit = {},
    onToggleWifiAware: () -> Unit = {},
    onToggleUltrasonicModem: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val btnShape = tacticalButtonShape(state.isSimpleMode, p.isOriginal, p.isAero)
    val activePillFg = if (p.isLight || p.isAero) Color.White else Color.Black

    var selectedScriptTab by remember { mutableStateOf("service.sh") }
    var selectedBrandFilter by remember { mutableStateOf(state.selectedPhoneProfile.brand) }
    var catalogSearchQuery by remember { mutableStateOf("") }

    val serviceShCode = remember(state.selectedPhoneProfile, state.wifiBeaconStuffingEnabled, state.unthrottledScanEnabled, state.systemlessMountEnabled) {
        """
        #!/system/bin/sh
        # PulseMesh KernelSU Next / SusFS Super-Node Booster Service
        # Target Hardware: ${state.selectedPhoneProfile.modelName} (${state.selectedPhoneProfile.socName})
        MODDIR="${'$'}{0%/*}"

        # 1. Bypass OEM Background Freezers (${state.selectedPhoneProfile.oemBackgroundKiller})
        echo 0 > /sys/module/lowmemorykiller/parameters/enable_lmk 2>/dev/null
        echo 1 > /dev/cpuset/foreground/boost/cgroup.procs 2>/dev/null
        device_config put activity_manager max_phantom_processes 2147483647
        ${state.selectedPhoneProfile.rootCommandProfile}

        # 2. Force 2.4GHz 802.11b Beacon Stuffing (+${state.selectedPhoneProfile.maxTxPowerDbm} dBm / 316 mW)
        ${if (state.wifiBeaconStuffingEnabled) "iw dev wlan0 set txpower fixed ${state.selectedPhoneProfile.maxTxPowerDbm}00\niwpriv wlan0 set_tx_power ${state.selectedPhoneProfile.maxTxPowerDbm}" else "# Wi-Fi boost disabled"}

        # 3. Bypass Android BLE Scan Throttling (100% Continuous RX Window)
        ${if (state.unthrottledScanEnabled) "settings put global ble_scan_low_latency_interval_ms 0\nsettings put global ble_scan_low_latency_window_ms 1000" else "# Scan throttling enabled"}

        # 4. Silicon Firmware Configuration Injection
        ${if (state.systemlessMountEnabled && state.selectedPhoneProfile.wcnssConfigPath != null) "mount -o bind ${'$'}MODDIR/WCNSS_qcom_cfg.ini ${state.selectedPhoneProfile.wcnssConfigPath}" else "# Systemless mount not applicable for this driver"}
        """.trimIndent()
    }

    val wcnssCfgCode = remember(state.selectedPhoneProfile, state.bleCodedPhyEnabled) {
        """
        # Calibration - ${state.selectedPhoneProfile.modelName}
        # Driver: ${state.selectedPhoneProfile.wifiBleDriver}
        gTxPower2g=${state.selectedPhoneProfile.maxTxPowerDbm}
        gTxPower5g=23
        gEnablePacketLog=0
        gEnableFastTransition=1
        gEnableMuBformee=1
        # BLE Coded PHY S=8 Link Budget (+12 dB FEC)
        gBleCodedPhyEnable=${if (state.bleCodedPhyEnabled) 1 else 0}
        gBleTxPowerMax=20
        # 802.11b 1Mbps DSSS Beacon Stuffing Vendor IE 0xDD
        gCustomVendorIeEnable=1
        gCustomVendorIeOUI=0050F2DD
        """.trimIndent()
    }

    val modulePropCode = remember(state.rangeMultiplier, state.selectedPhoneProfile) {
        """
        id=pulsemesh_supernode
        name=PulseMesh Super-Node (${state.selectedPhoneProfile.modelName})
        version=v2.8.0
        versionCode=280
        author=PulseMesh Tactical
        description=Unlocks +${state.selectedPhoneProfile.maxTxPowerDbm}dBm TX power, BLE Coded PHY S=8, and ${state.selectedPhoneProfile.oemBackgroundKiller} bypass for ${state.selectedPhoneProfile.modelName}.
        """.trimIndent()
    }

    val currentScriptContent = when (selectedScriptTab) {
        "service.sh" -> serviceShCode
        "WCNSS_qcom_cfg.ini" -> wcnssCfgCode
        else -> modulePropCode
    }

    val fullBundleContent = remember(serviceShCode, wcnssCfgCode, modulePropCode) {
        """
        # === 1. module.prop ===
        $modulePropCode

        # === 2. service.sh ===
        $serviceShCode

        # === 3. WCNSS_qcom_cfg.ini ===
        $wcnssCfgCode
        """.trimIndent()
    }

    var boosterTab by remember(state.isSimpleMode) { mutableStateOf(if (state.isSimpleMode) 0 else 1) }
    val activeTunersCount = listOf(
        state.bleCodedPhyEnabled,
        state.wifiBeaconStuffingEnabled,
        state.unthrottledScanEnabled,
        state.systemlessMountEnabled,
        state.ultrasonicModemEnabled,
    ).count { it }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .aeroCanvasBackground(p)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .testTag("root_booster_screen_list"),
        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 78.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Booster Mode Selector: Simple Range Booster vs Pro Kernel Console
        item {
            val switcherShape = if (p.isAero) RoundedCornerShape(18.dp) else RoundedCornerShape(8.dp)
            val pillShape = if (p.isAero) RoundedCornerShape(14.dp) else RoundedCornerShape(6.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(switcherShape)
                    .then(
                        if (p.isAero) {
                            Modifier.aeroGlassPanel(
                                palette = p,
                                cornerRadius = 18.dp,
                                tint = if (p.isDarkAero) Color(0xD40A2338) else Color(0xECFFFFFF),
                                borderColor = p.border,
                            )
                        } else {
                            Modifier
                                .background(p.card)
                                .border(1.dp, p.border, switcherShape)
                        }
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(pillShape)
                        .then(
                            if (boosterTab == 0) {
                                if (p.isAero) Modifier.aeroGlossOverlay(p, p.emerald, pillShape, 14.dp)
                                else Modifier.background(p.emerald)
                            } else Modifier.background(Color.Transparent)
                        )
                        .clickable { boosterTab = 0 }
                        .padding(vertical = 8.dp)
                        .testTag("booster_mode_simple_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Booster",
                            tint = if (boosterTab == 0) activePillFg else p.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "SIMPLE BOOSTER",
                            color = if (boosterTab == 0) activePillFg else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(pillShape)
                        .then(
                            if (boosterTab == 1) {
                                if (p.isAero) Modifier.aeroGlossOverlay(p, p.amber, pillShape, 14.dp)
                                else Modifier.background(p.amber)
                            } else Modifier.background(Color.Transparent)
                        )
                        .clickable { boosterTab = 1 }
                        .padding(vertical = 8.dp)
                        .testTag("booster_mode_pro_tab"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Pro",
                            tint = if (boosterTab == 1) activePillFg else p.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PRO KERNEL CONSOLE",
                            color = if (boosterTab == 1) activePillFg else p.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (boosterTab == 0) {
            // Card 1: Friendly Range & Power Booster
            item {
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = p.emerald,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (p.isAero) {
                                    AeroGlossBadgeIcon(
                                        imageVector = Icons.Default.Bolt,
                                        accentColor = p.emerald,
                                        contentDescription = "Booster",
                                        badgeSize = 38.dp,
                                        iconSize = 20.dp,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(p.emeraldDim),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = "Booster",
                                            tint = p.emerald,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "ANTENNA RANGE BOOSTER",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = p.emerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "${state.selectedPhoneProfile.modelName} • ${state.selectedPhoneProfile.socFamily.familyName}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = p.textSecondary,
                                            fontSize = 9.sp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Surface(
                                color = if (p.isAero) p.emeraldDim else p.background,
                                shape = RoundedCornerShape(if (p.isAero) 12.dp else 6.dp),
                                border = BorderStroke(1.dp, p.emerald)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("RANGE", color = p.textMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "~${String.format("%.1f", 1.2f * state.rangeMultiplier)} km",
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Boosts your phone's wireless transmitter so your messages and GPS pins travel farther through trees, terrain, and buildings.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textPrimary,
                                fontSize = 11.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Quick Boost Toggles
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BoosterToggleItem(
                                title = "Long-Range Coded Signals (+8 dBm)",
                                subtitle = "Extends transmission range 4x through foliage and terrain.",
                                isChecked = state.bleCodedPhyEnabled,
                                onCheckedChange = onToggleBleCoded
                            )

                            BoosterToggleItem(
                                title = "Continuous Background Listening",
                                subtitle = "Prevents Android from putting Bluetooth to sleep when locked.",
                                isChecked = state.unthrottledScanEnabled,
                                onCheckedChange = onToggleUnthrottledScan
                            )

                            BoosterToggleItem(
                                title = "Wi-Fi Beacon Power Injection",
                                subtitle = "Injects tactical packets into 2.4 GHz 802.11b beacon frames.",
                                isChecked = state.wifiBeaconStuffingEnabled,
                                onCheckedChange = onToggleWifiStuffing
                            )

                            BoosterToggleItem(
                                title = "Wi-Fi Aware / NAN Discovery (Optional)",
                                subtitle = "Off by default to prevent Wi-Fi Direct / Personal Hotspot radio resets.",
                                isChecked = state.wifiAwareEnabled,
                                onCheckedChange = onToggleWifiAware
                            )

                            BoosterToggleItem(
                                title = "Near-Ultrasonic Acoustic Air-Gap Modem (18.5 kHz BFSK)",
                                subtitle = "Zero-RF room-scale call/SOS signaling over speaker & mic when RF is jammed.",
                                isChecked = state.ultrasonicModemEnabled,
                                onCheckedChange = onToggleUltrasonicModem
                            )
                        }
                    }
                }
            }

            // Card 2: 1-Phone Radio Self-Test & Scanner
            item {
                LiveBleDiagnosticPanel(
                    txState = state.bleTxState,
                    isRxSnifferActive = state.isBleRxSnifferActive,
                    scannedDevices = state.scannedBleDevices,
                    totalRxPackets = state.totalBleRxPackets,
                    onStartTx = onStartBleTx,
                    onStopTx = onStopBleTx,
                    onToggleRxSniffer = onToggleRxSniffer,
                    onClearDevices = onClearBleDevices,
                    onRequestPermissions = onRequestPermissions,
                    connectedServerDevices = state.connectedServerDeviceAddresses,
                    connectedGattClients = state.connectedGattClientAddresses,
                    isTwoWaySymmetric = state.isTwoWayGattSymmetric,
                    onForceGattReDiscovery = onForceGattReDiscovery
                )
            }

            // Card 3: Plain-English Root Status
            item {
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = if (state.isRootGranted) p.emerald else p.border,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                if (p.isAero) {
                                    AeroGlossBadgeIcon(
                                        imageVector = if (state.isRootGranted) Icons.Default.Security else Icons.Default.Info,
                                        accentColor = if (state.isRootGranted) p.emerald else p.cyan,
                                        contentDescription = "Root",
                                        badgeSize = 32.dp,
                                        iconSize = 17.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (state.isRootGranted) Icons.Default.Security else Icons.Default.Info,
                                        contentDescription = "Root",
                                        tint = if (state.isRootGranted) p.emerald else p.cyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (state.isRootGranted) "KERNELSU NEXT SUPER-NODE ACTIVE" else "STANDARD RADIO MODE (+14 dBm)",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = if (state.isRootGranted) p.emerald else p.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    )
                                    Text(
                                        text = if (state.isRootGranted) "Hardware unlocked to +${state.selectedPhoneProfile.maxTxPowerDbm} dBm" else "Standard legal power (works on all phones without root)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = p.textSecondary,
                                            fontSize = 9.sp
                                        )
                                    )
                                }
                            }

                            val rootBtnBg = if (state.isRootGranted) p.emerald else p.cyan
                            Button(
                                onClick = onVerifyRoot,
                                enabled = !state.isRootVerifying,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else {
                                        if (state.isRootGranted) p.surfaceVariant else p.cyan
                                    },
                                    contentColor = if (p.isAero) activePillFg else {
                                        if (state.isRootGranted) p.emerald else activePillFg
                                    }
                                ),
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .then(
                                        if (p.isAero) Modifier.aeroGlossOverlay(p, rootBtnBg, btnShape, 12.dp)
                                        else Modifier
                                    )
                            ) {
                                if (state.isRootVerifying) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = activePillFg, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CHECKING...", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(if (state.isRootGranted) "RE-CHECK" else "VERIFY ROOT", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // PRO KERNEL CONSOLE
            // 0. Quick Pro RF Telemetry Strip
            item {
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = p.borderBright,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pro_rf_telemetry_strip")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProStatTile(
                            label = "MAX TX",
                            value = "+${state.selectedPhoneProfile.maxTxPowerDbm} dBm",
                            accent = p.amber,
                            modifier = Modifier.weight(1f)
                        )
                        ProStatTile(
                            label = "RANGE",
                            value = "${String.format("%.1f", state.rangeMultiplier)}x",
                            accent = p.emerald,
                            modifier = Modifier.weight(1f)
                        )
                        ProStatTile(
                            label = "TUNERS",
                            value = "$activeTunersCount/5 ON",
                            accent = p.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        ProStatTile(
                            label = "ROOT SU",
                            value = if (state.isRootGranted) "UID 0" else "LOCKED",
                            accent = if (state.isRootGranted) p.emerald else p.red,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 1. Real KernelSU Next Root Verification Card
            item {
                val rootBorder = if (state.isRootGranted) p.emerald else p.red
                val rootTint = if (state.isRootGranted) {
                    if (p.isDarkAero) Color(0xD40A2338) else Color(0xECFFFFFF)
                } else {
                    if (p.isAero) p.redDim.copy(alpha = 0.85f) else p.redDim
                }
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = rootBorder,
                    customNonAeroBg = if (state.isRootGranted) p.card else p.redDim,
                    customAeroTint = rootTint,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                if (p.isAero) {
                                    AeroGlossBadgeIcon(
                                        imageVector = if (state.isRootGranted) Icons.Default.Security else Icons.Default.GppBad,
                                        accentColor = rootBorder,
                                        contentDescription = "Root Status",
                                        badgeSize = 34.dp,
                                        iconSize = 18.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (state.isRootGranted) Icons.Default.Security else Icons.Default.GppBad,
                                        contentDescription = "Root Status",
                                        tint = rootBorder,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (state.isRootGranted) "KERNELSU NEXT ROOT VERIFIED" else "ROOT NOT GRANTED",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = rootBorder,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = if (state.isRootGranted) "uid=0(root) confirmed" else "KernelSU Next grant required",
                                        style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                                    )
                                }
                            }

                            val verifyBg = if (state.isRootGranted) p.emerald else p.red
                            val verifyFg = if (state.isRootGranted) activePillFg else Color.White
                            Button(
                                onClick = onVerifyRoot,
                                enabled = !state.isRootVerifying,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else {
                                        if (state.isRootGranted) p.surfaceVariant else p.red
                                    },
                                    contentColor = if (p.isAero) verifyFg else {
                                        if (state.isRootGranted) p.emerald else Color.White
                                    }
                                ),
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .then(
                                        if (p.isAero) Modifier.aeroGlossOverlay(p, verifyBg, btnShape, 12.dp)
                                        else Modifier
                                    )
                            ) {
                                if (state.isRootVerifying) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = verifyFg,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CHECKING...", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Verify",
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (state.isRootGranted) "RE-VERIFY" else "VERIFY ROOT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = state.rootStatusMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textPrimary,
                                fontWeight = if (state.isRootGranted) FontWeight.Normal else FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        )

                        if (state.rootProbeLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            var showLogs by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLogs = !showLogs },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (showLogs) "Hide Process Logs ▲" else "View su -c id Probe Output ▼",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.cyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            if (showLogs) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                                        .background(if (p.isLight || (p.isAero && !p.isDarkAero)) Color(0xFF0A2033) else p.background)
                                        .border(1.dp, p.border, RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = state.rootProbeLogs.joinToString("\n"),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (p.isLight || (p.isAero && !p.isDarkAero)) Color(0xFF9DF6FF) else p.textSecondary,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Live Radio Hardware Diagnostic & BLE Sniffer (1-Phone Proof)
            item {
                LiveBleDiagnosticPanel(
                    txState = state.bleTxState,
                    isRxSnifferActive = state.isBleRxSnifferActive,
                    scannedDevices = state.scannedBleDevices,
                    totalRxPackets = state.totalBleRxPackets,
                    onStartTx = onStartBleTx,
                    onStopTx = onStopBleTx,
                    onToggleRxSniffer = onToggleRxSniffer,
                    onClearDevices = onClearBleDevices,
                    onRequestPermissions = onRequestPermissions,
                    connectedServerDevices = state.connectedServerDeviceAddresses,
                    connectedGattClients = state.connectedGattClientAddresses,
                    isTwoWaySymmetric = state.isTwoWayGattSymmetric,
                    onForceGattReDiscovery = onForceGattReDiscovery
                )
            }

            // 3. Super-Node Master Status Card
            item {
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = p.amber,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (p.isAero) {
                                    AeroGlossBadgeIcon(
                                        imageVector = Icons.Default.Bolt,
                                        accentColor = p.amber,
                                        contentDescription = "Super-Node",
                                        badgeSize = 34.dp,
                                        iconSize = 18.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = "Super-Node",
                                        tint = p.amber,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "ROOT RF SUPER-NODE BOOSTER",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = p.amber,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "KERNELSU NEXT + SUSFS HARDWARE TUNER",
                                        style = MaterialTheme.typography.labelSmall.copy(color = p.textSecondary)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                                    .background(p.amberDim)
                                    .border(1.dp, p.amber, RoundedCornerShape(if (p.isAero) 10.dp else 4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${String.format("%.1f", state.rangeMultiplier)}x RANGE",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = p.amber,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Unlocks Class-1 radio registers, Vendor IE 0xDD beacon stuffing, and custom power tables to achieve multi-kilometer direct phone-to-phone links.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = p.textPrimary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            // 4. Side-by-Side: Real Detected Hardware vs Selected Profile (+ 1-tap Auto-Match)
            item {
                val isMismatched = state.hardwareMismatchReport?.isMismatched == true
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = if (isMismatched) p.red else p.border,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(11.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "HARDWARE INTEGRITY VERIFICATION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.cyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(if (p.isAero) 8.dp else 4.dp))
                                    .background(if (isMismatched) p.redDim else p.emeraldDim)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isMismatched) "MISMATCH DETECTED" else "SILICON MATCHED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isMismatched) p.red else p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "REAL DETECTED HARDWARE:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textMuted,
                                        fontSize = 8.sp
                                    )
                                )
                                Text(
                                    text = "${state.detectedHardware?.manufacturer ?: ""} ${state.detectedHardware?.model ?: "Host"}".trim(),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                )
                                Text(
                                    text = "GPU: ${state.detectedHardware?.gpuRenderer ?: "Unknown GLES"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textSecondary,
                                        fontSize = 8.sp
                                    )
                                )
                            }

                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "SELECTED PROFILE:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textMuted,
                                        fontSize = 8.sp
                                    )
                                )
                                Text(
                                    text = state.selectedPhoneProfile.modelName,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (isMismatched) p.red else p.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                )
                                Text(
                                    text = state.selectedPhoneProfile.socName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textSecondary,
                                        fontSize = 8.sp
                                    )
                                )
                            }
                        }

                        // Mismatch warning banner + 1-Tap Auto-Match inside card
                        val mismatchReport = state.hardwareMismatchReport
                        if (mismatchReport?.isMismatched == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HardwareMismatchWarningBanner(
                                report = mismatchReport,
                                onClick = onOpenMismatchDialog
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = onAutoSwitchToDetected,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (p.isAero) Color.Transparent else p.emerald,
                                    contentColor = activePillFg
                                ),
                                shape = btnShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .then(
                                        if (p.isAero) Modifier.aeroGlossOverlay(p, p.emerald, btnShape, 12.dp)
                                        else Modifier
                                    )
                                    .testTag("pro_auto_match_silicon_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = "Auto-Match",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AUTO-MATCH DETECTED SILICON",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 5. MASSIVE 40+ PHONE & CHIPSET SELECTOR (WITH SEARCH + DETECTED MATCH BADGE)
            item {
                var isCatalogExpanded by remember { mutableStateOf(false) }
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = p.border,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(11.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCatalogExpanded = !isCatalogExpanded }
                                .testTag("pro_catalog_toggle_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PHONE & CHIPSET SELECTOR (${DeviceCatalog.allModels.size} MODELS)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                )
                                Text(
                                    text = "Active: ${state.selectedPhoneProfile.modelName} (${state.selectedPhoneProfile.socName})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textSecondary,
                                        fontSize = 8.sp
                                    )
                                )
                            }
                            Text(
                                text = if (isCatalogExpanded) "HIDE CATALOG ▲" else "EXPAND CATALOG ▼",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = p.cyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        if (isCatalogExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = catalogSearchQuery,
                                onValueChange = { catalogSearchQuery = it },
                                placeholder = { Text("Search model, chipset, or driver...", fontSize = 10.sp, color = p.textMuted) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = p.cyan, modifier = Modifier.size(16.dp))
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = p.textPrimary,
                                    unfocusedTextColor = p.textPrimary,
                                    focusedBorderColor = p.cyan,
                                    unfocusedBorderColor = p.border,
                                    focusedContainerColor = if (p.isAero) p.surface else p.background,
                                    unfocusedContainerColor = if (p.isAero) p.surface else p.background,
                                ),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                shape = RoundedCornerShape(if (p.isAero) 12.dp else 6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pro_catalog_search_input")
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Brand Category Tabs
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(DeviceBrand.values()) { brand ->
                                    val isSelected = selectedBrandFilter == brand && catalogSearchQuery.isBlank()
                                    val chipShape = RoundedCornerShape(if (p.isAero) 10.dp else 4.dp)
                                    Box(
                                        modifier = Modifier
                                            .clip(chipShape)
                                            .then(
                                                if (isSelected && p.isAero) {
                                                    Modifier.aeroGlossOverlay(p, p.emerald, chipShape, 10.dp)
                                                } else {
                                                    Modifier.background(if (isSelected) p.emerald else p.surfaceVariant)
                                                }
                                            )
                                            .border(1.dp, if (isSelected) p.emerald else p.border, chipShape)
                                            .clickable {
                                                catalogSearchQuery = ""
                                                selectedBrandFilter = brand
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = brand.displayName,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) activePillFg else p.textSecondary,
                                                fontSize = 8.sp
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            val q = catalogSearchQuery.trim()
                            val modelsToShow = if (q.isNotBlank()) {
                                DeviceCatalog.allModels.filter {
                                    it.modelName.contains(q, ignoreCase = true) ||
                                        it.socName.contains(q, ignoreCase = true) ||
                                        it.wifiBleDriver.contains(q, ignoreCase = true) ||
                                        it.brand.displayName.contains(q, ignoreCase = true)
                                }
                            } else {
                                DeviceCatalog.allModels.filter { it.brand == selectedBrandFilter }
                            }

                            if (modelsToShow.isEmpty()) {
                                Text(
                                    text = "No hardware profiles match \"$q\".",
                                    style = MaterialTheme.typography.labelSmall.copy(color = p.textMuted, fontSize = 9.sp),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                modelsToShow.forEach { profile ->
                                    val isSelected = state.selectedPhoneProfile.id == profile.id
                                    val isHostMatch = state.detectedHardware?.detectedSocFamily == profile.socFamily
                                    val isMismatched = state.detectedHardware != null && !isHostMatch

                                    val rowBg = when {
                                        isSelected && isMismatched -> p.redDim
                                        isSelected -> p.emeraldDim
                                        else -> p.surfaceVariant
                                    }
                                    val rowBorder = when {
                                        isSelected && isMismatched -> p.red
                                        isSelected -> p.emerald
                                        else -> p.border
                                    }

                                    Surface(
                                        color = rowBg,
                                        shape = RoundedCornerShape(if (p.isAero) 12.dp else 6.dp),
                                        border = BorderStroke(1.dp, rowBorder),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clickable { onSelectPhoneProfile(profile) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { onSelectPhoneProfile(profile) },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = if (isMismatched) p.red else p.emerald,
                                                    unselectedColor = p.textMuted
                                                ),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = profile.modelName,
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            color = if (isSelected) p.textPrimary else p.textSecondary,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp
                                                        )
                                                    )
                                                    if (isHostMatch) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[HOST MATCH]",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = p.emerald,
                                                                fontSize = 7.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        )
                                                    } else if (isMismatched && isSelected) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "[MISMATCH]",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = p.red,
                                                                fontSize = 7.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "${profile.socName} • ${profile.wifiBleDriver}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = p.textMuted,
                                                        fontSize = 8.sp
                                                    )
                                                )
                                                Text(
                                                    text = "Killer: ${profile.oemBackgroundKiller}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = p.cyan,
                                                        fontSize = 7.sp
                                                    )
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(if (p.isAero) 8.dp else 3.dp))
                                                    .background(p.amberDim)
                                                    .border(1.dp, p.amber.copy(alpha = 0.6f), RoundedCornerShape(if (p.isAero) 8.dp else 3.dp))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "+${profile.maxTxPowerDbm}dBm",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = p.amber,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Hardware Tuner Toggles
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BoosterToggleItem(
                        title = "Force BLE Coded PHY S=8 & Class 1 (+20 dBm)",
                        subtitle = "125 kbps FEC encoding adds +12 dB link budget for extreme non-line-of-sight range.",
                        isChecked = state.bleCodedPhyEnabled,
                        onCheckedChange = { onToggleBleCoded() }
                    )

                    BoosterToggleItem(
                        title = "2.4 GHz 802.11b Beacon Stuffing (+${state.selectedPhoneProfile.maxTxPowerDbm} dBm)",
                        subtitle = "Injects 16-byte GPS & voice packets into Vendor IE 0xDD beacon frames via driver netlink.",
                        isChecked = state.wifiBeaconStuffingEnabled,
                        onCheckedChange = { onToggleWifiStuffing() }
                    )

                    BoosterToggleItem(
                        title = "Bypass Android BLE Scan Throttling (100% RX Window)",
                        subtitle = "Eliminates Android 30-minute scan cooldowns for instant screen-off packet reception.",
                        isChecked = state.unthrottledScanEnabled,
                        onCheckedChange = { onToggleUnthrottledScan() }
                    )

                    BoosterToggleItem(
                        title = "Systemless Firmware Mount (KernelSU Next)",
                        subtitle = "Injects calibrations into ${state.selectedPhoneProfile.wcnssConfigPath ?: "/vendor/etc/wifi/"} without modifying /vendor.",
                        isChecked = state.systemlessMountEnabled,
                        onCheckedChange = { onToggleSystemlessMount() }
                    )

                    BoosterToggleItem(
                        title = "Near-Ultrasonic Acoustic Air-Gap Modem (18.1 / 18.9 kHz BFSK)",
                        subtitle = "32-bit CRC4 Goertzel acoustic signaling for CALL_RING / CALL_ACCEPT / SOS.",
                        isChecked = state.ultrasonicModemEnabled,
                        onCheckedChange = { onToggleUltrasonicModem() }
                    )
                }
            }

            // 7. Copyable KernelSU Next Module Generator
            item {
                AeroThemedCard(
                    palette = p,
                    shape = cardShape,
                    borderColor = p.border,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(11.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "KERNELSU NEXT MODULE GENERATOR",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.emerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                )
                                Text(
                                    text = "${currentScriptContent.lines().size} lines • Target: ${state.selectedPhoneProfile.wcnssConfigPath ?: "/vendor/etc/wifi/"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = p.textMuted,
                                        fontSize = 7.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("PulseMesh_KernelSU_Bundle", fullBundleContent)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "All 3 module files copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(if (p.isAero) 10.dp else 6.dp),
                                    border = BorderStroke(1.dp, p.cyan),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = "COPY ALL",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = p.cyan
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText(selectedScriptTab, currentScriptContent)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "$selectedScriptTab copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(if (p.isAero) 10.dp else 6.dp))
                                        .background(p.emeraldDim)
                                        .border(1.dp, p.emerald, RoundedCornerShape(if (p.isAero) 10.dp else 6.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Script",
                                        tint = p.emerald,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("service.sh", "WCNSS_qcom_cfg.ini", "module.prop").forEach { tab ->
                                val isSelected = selectedScriptTab == tab
                                val tabShape = RoundedCornerShape(if (p.isAero) 10.dp else 4.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(tabShape)
                                        .then(
                                            if (isSelected && p.isAero) {
                                                Modifier.aeroGlossOverlay(p, p.emerald, tabShape, 10.dp)
                                            } else {
                                                Modifier.background(if (isSelected) p.emerald else p.surfaceVariant)
                                            }
                                        )
                                        .border(1.dp, if (isSelected) p.emerald else p.border, tabShape)
                                        .clickable { selectedScriptTab = tab }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = tab,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (isSelected) activePillFg else p.textSecondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val consoleBg = when {
                            p.isDarkAero -> Color(0xCC041422)
                            p.isAero || p.isLight -> Color(0xFF0A2033)
                            else -> p.background
                        }
                        val consoleTextColor = if (p.isLight || p.isAero) Color(0xFF66FFC2) else p.emerald
                        Surface(
                            color = consoleBg,
                            shape = RoundedCornerShape(if (p.isAero) 14.dp else 6.dp),
                            border = BorderStroke(1.dp, if (p.isAero) p.borderBright else p.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(9.dp)
                            ) {
                                Text(
                                    text = currentScriptContent,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = consoleTextColor,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
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
private fun AeroThemedCard(
    palette: PulseMeshPalette,
    shape: Shape,
    borderColor: Color,
    modifier: Modifier = Modifier,
    customNonAeroBg: Color? = null,
    customAeroTint: Color? = null,
    content: @Composable () -> Unit
) {
    Surface(
        color = if (palette.isAero) Color.Transparent else (customNonAeroBg ?: palette.card),
        shape = shape,
        border = if (palette.isAero) null else BorderStroke(1.dp, borderColor),
        modifier = modifier.then(
            if (palette.isAero) {
                Modifier.aeroGlassPanel(
                    palette = palette,
                    cornerRadius = 22.dp,
                    tint = customAeroTint ?: if (palette.isDarkAero) Color(0xD40A2338) else Color(0xEAFFFFFF),
                    borderColor = borderColor,
                )
            } else Modifier
        ),
        content = content
    )
}

@Composable
private fun ProStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val p = LocalPulsePalette.current
    Surface(
        color = if (p.isAero) accent.copy(alpha = 0.14f) else p.background,
        shape = RoundedCornerShape(if (p.isAero) 12.dp else 6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                color = p.textMuted,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoosterToggleItem(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: () -> Unit
) {
    val p = LocalPulsePalette.current
    val cardShape = tacticalCardShape(false, p.isOriginal, p.isAero)
    val activeAccent = if (p.isAero) p.cyan else p.amber
    val borderColor = if (isChecked) activeAccent else p.border

    AeroThemedCard(
        palette = p,
        shape = cardShape,
        borderColor = borderColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (isChecked) activeAccent else p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = p.textSecondary,
                        fontSize = 9.sp
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = isChecked,
                onCheckedChange = { onCheckedChange() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (p.isLight || p.isAero) Color.White else activeAccent,
                    checkedTrackColor = if (p.isLight || p.isAero) activeAccent else activeAccent.copy(alpha = 0.25f),
                    uncheckedThumbColor = p.textMuted,
                    uncheckedTrackColor = p.surfaceVariant,
                    uncheckedBorderColor = p.border,
                )
            )
        }
    }
}
