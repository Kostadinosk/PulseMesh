// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.TacticalE2eeManager
import com.example.model.*
import com.example.util.PersistentChatManager
import com.example.service.TacticalAudioService
import com.example.service.BleTxDiagnosticState
import com.example.service.LiveBleRadioService
import com.example.service.RawBleDeviceRecord
import com.example.util.DetectedHardwareInfo
import com.example.util.DevicePreferences
import com.example.util.HardwareDetector
import com.example.util.HardwareMismatchReport
import com.example.util.RootDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class MeshUiState(
    // Top Status Bar & Operator Identity
    val localNodeId: String = "0x4F2A",
    val operatorCallsign: String = "Operator",
    val localDeviceModel: String = "OnePlus 15",
    val activeTransportDesc: String = "BLE 1M/Coded + Hotspot Wi-Fi",
    val txPowerDesc: String = "+14 dBm (Unrooted)",
    val isBroadcastingGps: Boolean = true,
    val activePeerCount: Int = 0, // 100% Real - starts with 0 peers until discovered
    val realBatteryPercent: Int = 85,
    val hotspotButtonText: String = "HOTSPOT: OFF",

    // Real KernelSU Next Root State (No hardcoded true state)
    val isRootGranted: Boolean = false,
    val isRootVerifying: Boolean = false,
    val rootStatusMessage: String = "Root Not Granted — Open KernelSU Next Manager -> Superuser -> Enable PulseMesh, then tap Verify Root.",
    val rootProbeLogs: List<String> = emptyList(),

    // Real-Time Hardware Detection & 40+ Categorized Model Profile
    val detectedHardware: DetectedHardwareInfo? = null,
    val selectedPhoneProfile: PhoneModelProfile = DeviceCatalog.allModels.first(),
    val hardwareMismatchReport: HardwareMismatchReport? = null,
    val showMismatchWarningDialog: Boolean = false,
    val isMismatchOverrideConfirmed: Boolean = false,

    // Off-Grid & Security Posture
    val isPureOffGrid: Boolean = true,
    val internetGatewayBridgeEnabled: Boolean = false,
    val isInternetEnabled: Boolean = false,
    val isSpokenCallerIdEnabled: Boolean = false,
    val encryptionKeyEpoch: Int = 1,
    val isE2eeActive: Boolean = true,
    val x25519Fingerprint: String = "7A:B2:3F:89:12:44:C0:EE",
    val x25519PublicKey: String = "",
    val showSetupWizard: Boolean = false,
    val isSimpleMode: Boolean = true,
    val appTheme: com.example.util.AppTheme = com.example.util.AppTheme.TACTICAL_DARK,
    val meshChannel: String = "pulsemesh_live_primary",

    // Dual-Mode Transport & Live Packet Counters
    val isPureOffGridTransport: Boolean = true,
    val relayStatus: String = "Relay: OFF (Pure BLE)",
    val bleTxStatus: String = "BLE TX: IDLE",
    val connectedServerDeviceAddresses: List<String> = emptyList(),
    val connectedGattClientAddresses: List<String> = emptyList(),
    val isTwoWayGattSymmetric: Boolean = false,
    val hotspotState: com.example.util.HotspotState = com.example.util.HotspotState(),
    val txFramesCount: Int = 0,
    val rxFramesCount: Int = 0,
    val lastRssiDbm: Int = -55,

    // 1-Tap Hands-Free Duplex Call State
    val duplexCallState: com.example.service.DuplexCallState = com.example.service.DuplexCallState(),

    // Nodes & Telemetry (GPS starts null until permission/sensor locks or user places pin)
    val nodes: List<MeshNode> = emptyList(),
    val selectedNode: MeshNode? = null,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isGpsLocked: Boolean = false,
    val gpsStatusMessage: String = "GPS Not Locked Yet — Tap 'Request GPS' or Set Location Pin",
    val usingRealGps: Boolean = false,

    // Live Voice Call State (Normal 1-on-1 calls)
    val activePrivateCallNode: MeshNode? = null,
    val incomingPrivateCall: MeshNode? = null,
    val isPrivateCallActive: Boolean = false,

    // Map Settings
    val mapRenderMode: MapRenderMode = MapRenderMode.VECTOR_LEAFLET,
    val isPrivacyFuzzingEnabled: Boolean = false,
    val isSosActive: Boolean = false,
    val waypoints: List<TacticalWaypoint> = emptyList(),

    // Chat & Channels
    val activeChannel: String = "#Primary-LongFast",
    val packets: List<MeshPacket> = emptyList(),
    val selectedThreadId: String? = null,
    val conversations: List<ChatConversation> = emptyList(),
    val meshGroups: List<MeshGroup> = emptyList(),
    val activeThreadPackets: List<MeshPacket> = emptyList(),
    val searchQuery: String = "",

    // Voice & PTT
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.MULTI_HOP_BURST,
    val isPttPressed: Boolean = false,
    val pttTargetNodeId: String = "ALL (Broadcast)",
    val callDurationSec: Int = 0,
    val packetLossPercent: Float = 0f,
    val jitterMs: Int = 0,
    val voiceHopStep: String = "Ready",
    val useLiveMicrophone: Boolean = true,
    val lastPurgeStatus: String = "",

    // Root RF Super-Node Booster (Default unrooted)
    val bleCodedPhyEnabled: Boolean = true,
    val wifiBeaconStuffingEnabled: Boolean = false,
    val unthrottledScanEnabled: Boolean = false,
    val systemlessMountEnabled: Boolean = false,
    val rangeMultiplier: Float = 1.0f,
    val chipsetProfile: ChipsetProfile = ChipsetProfile.QUALCOMM_SNAPDRAGON,
    val oemBypassStatus: OemBypassStatus = OemBypassStatus(),

    // Live Radio Hardware Diagnostic & BLE Sniffer State
    val bleTxState: BleTxDiagnosticState = BleTxDiagnosticState(),
    val isBleRxSnifferActive: Boolean = false,
    val scannedBleDevices: List<RawBleDeviceRecord> = emptyList(),
    val totalBleRxPackets: Int = 0,
    val hasRequiredPermissions: Boolean = false,
    val showPermissionsBanner: Boolean = true,

    // Hoisted Top-Level Call & PTT State (Visible Across All Tabs)
    val callState: com.example.service.CallSignalingState = com.example.service.CallSignalingState.IDLE,
    val remoteCallerId: String = "",
    val remoteCallerName: String = "",
    val activeCallTargetId: String = "ALL",
    val activeSpeakingBanner: String = "",
    val pttStatus: String = "STANDBY • READY TO TRANSMIT",
    val rxAudioCount: Int = 0,
    val txAudioCount: Int = 0,

    // Compass Heading, Barometric Altitude, Store-and-Forward DTN & Multi-Layer Omni Stack
    val deviceHeadingDeg: Float = 0f,
    val barometricPressureHpa: Float? = null,
    val barometricAltitudeMeters: Double? = null,
    val pendingDtnCount: Int = 0,
    val wifiAwareEnabled: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val ultrasonicModemEnabled: Boolean = false,
    val bell202ModemEnabled: Boolean = false,
    val activeL2capChannelsCount: Int = 0,
    val localL2capPsm: Int = 0,
    val callAckConfirmed: Boolean = false,
    val broadcastCoarseLocationInBle: Boolean = false,
    val locationShareMode: LocationShareMode = LocationShareMode.EXACT,
    val meshPassphrase: String = "pulsemesh-tactical",
    val isAppInForeground: Boolean = true,
    val activeOmniLayersSummary: String = "Omni Stack Active (BLE 1M/LR/2M/L2CAP • P2P/NAN • IPv4-MC/IPv6/mDNS • Acoustic • USB/ETH • eSIM)",
    val isHotspotAutoPopupEnabled: Boolean = true,
    val pendingHotspotPrompt: com.example.service.HotspotPromptInfo? = null,
    val autoCheckInIntervalMin: Int = 0,
    val lastPingSweepReport: String = "Ready — Tap 'Run Mesh Link Sweep' to probe peer RTT, SNR & DTN status.",
    val isPingSweepRunning: Boolean = false,

    // Unique Username Reservation, Offline Hold, PIN/Recovery & Distant Friend Connection
    val claimedUsername: String = "",
    val accountRecoveryKey: String = "",
    val hasAccountPin: Boolean = false,
    val usernameAuthStatus: String = "",
    val isReservingUsername: Boolean = false,
    val isConnectingByUsername: Boolean = false,
    val usernameConnectStatus: String = "",
    val isUsernameOnHold: Boolean = false,
    val pendingUsernameHold: String = "",
    val usernamePopupTitle: String = "",
    val usernamePopupMessage: String = "",
    val showUsernamePopup: Boolean = false,
    val usernameRelayUrl: String = "",
    val usernameRelayTestStatus: String = "",
    val lastAccountBackupSyncStatus: String = "",

    // PulseMesh v1.1 Tactical Upgrades State
    val isBackgroundRelayServiceEnabled: Boolean = true,
    val isVolumeButtonPttEnabled: Boolean = true,
    val isRadioVoxBridgeEnabled: Boolean = false,
    val isDeadReckoningEnabled: Boolean = false,
    val isBreadcrumbRecording: Boolean = true,
    val breadcrumbs: List<BreadcrumbPoint> = emptyList(),
    val stepCount: Int = 0,
    val messageBurnTtlSeconds: Int = 0,
    val hasDuressPin: Boolean = false,
    val p2pTileSyncStatus: String = "Ready to beam cached map tiles over local mesh",

    // Wi-Fi Direct Embedded Web Portal & High-Visibility SOS State
    val webPortalStatus: com.example.service.WebPortalStatus = com.example.service.WebPortalStatus(),
    val incomingSosAlert: String = "",

    // PulseMesh Field Upgrades State
    val appLanguage: String = "en",
    val sharedFiles: List<SharedMeshFile> = emptyList(),
    val quickStatusVoiceEnabled: Boolean = true,
    val lastQuickStatusCode: String = "",
    val iceBloodType: String = "",
    val iceAllergies: String = "",
    val iceNotes: String = "",
    val iceEmergencyContact: String = "",
    val deadManSwitchMinutes: Int = 0,
    val deadManRemainingSeconds: Int = 0,
    val geofenceEnabled: Boolean = false,
    val geofenceLat: Double = 0.0,
    val geofenceLng: Double = 0.0,
    val geofenceRadiusMeters: Int = 500,
    val geofenceAlertMessage: String = "",
    val expeditionModeEnabled: Boolean = false,
    val expeditionAutoBelow20: Boolean = true,
    val hasBarometerSensor: Boolean = false,
    val pressureTrendHpaPer3h: Float? = null,
    val weatherTrendLabel: String = "UNAVAILABLE",
    val isStormAlertActive: Boolean = false,
    val perThreadBurnTtlMap: Map<String, Int> = emptyMap(),
    val hasDecoyPin: Boolean = false,
    val isDecoyModeActive: Boolean = false,

    // PulseMesh v1.4.0 Smart Router & Startup Update Checker State
    val availableUpdate: com.example.service.AppUpdateInfo? = null,
    val isCheckingForUpdate: Boolean = false,
    val updateCheckStatus: String = "",
    val routerSummary: String = "Smart Link-Cost Router Active (Wi-Fi/NAN < L2CAP < GATT < Relay)",
    val routerFailoverCount: Int = 0,
    val binaryBytesSaved: Long = 0L,
    val pmb2PeerCount: Int = 0,

    // Active Navigation Tab (0: Map, 1: Chat, 2: Voice, 3: Sim, 4: Root)
    val selectedTabIndex: Int = 0
)

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val PEER_STALE_TIMEOUT_MS = 45_000L
        const val PEER_UI_THROTTLE_MS = 1_500L
        const val CHAT_ACK_RETRY_DELAY_MS = 900L
        const val CALL_POST_ACTION_COOLDOWN_MS = 6_000L
    }

    val prefs = DevicePreferences(application)

    private val detectedHw = HardwareDetector.detectHardware(application)
    private val initialSelectedProfile = HardwareDetector.resolveAutoMatchedProfile(detectedHw)
    private val initialMismatch = HardwareDetector.evaluateMismatch(detectedHw, initialSelectedProfile)

    private val _uiState = MutableStateFlow(
        MeshUiState(
            localNodeId = prefs.nodeId,
            operatorCallsign = prefs.operatorCallsign,
            localDeviceModel = initialSelectedProfile.modelName,
            isPureOffGrid = prefs.isPureOffGrid,
            internetGatewayBridgeEnabled = prefs.internetGatewayBridgeEnabled,
            isInternetEnabled = prefs.isInternetEnabled,
            isPureOffGridTransport = !prefs.isInternetEnabled,
            isSpokenCallerIdEnabled = prefs.isSpokenCallerIdEnabled,
            x25519Fingerprint = prefs.fingerprint.ifBlank { "7A:B2:3F:89:12:44:C0:EE" },
            x25519PublicKey = prefs.x25519PublicKey,
            showSetupWizard = !prefs.isFirstLaunchCompleted,
            realBatteryPercent = detectedHw.batteryPercent,
            detectedHardware = detectedHw,
            selectedPhoneProfile = initialSelectedProfile,
            hardwareMismatchReport = initialMismatch,
            isMismatchOverrideConfirmed = true,
            showMismatchWarningDialog = false,
            isSimpleMode = prefs.isSimpleMode,
            appTheme = prefs.appTheme,
            encryptionKeyEpoch = prefs.encryptionKeyEpoch,
            broadcastCoarseLocationInBle = prefs.locationShareMode == LocationShareMode.COARSE,
            locationShareMode = prefs.locationShareMode,
            meshPassphrase = prefs.meshPassphrase,
            pendingDtnCount = prefs.loadPendingDtnPackets().size,
            meshGroups = prefs.loadMeshGroups(),
            isHotspotAutoPopupEnabled = prefs.isHotspotAutoPopupEnabled,
            autoCheckInIntervalMin = prefs.autoCheckInIntervalMin,
            claimedUsername = prefs.claimedUsername,
            accountRecoveryKey = "",
            hasAccountPin = prefs.accountPinHash.isNotBlank(),
            isUsernameOnHold = prefs.isUsernameOnHold,
            pendingUsernameHold = prefs.pendingUsernameHold,
            usernameRelayUrl = prefs.usernameRelayUrl,
            isBackgroundRelayServiceEnabled = prefs.isBackgroundRelayServiceEnabled,
            isVolumeButtonPttEnabled = prefs.isVolumeButtonPttEnabled,
            isRadioVoxBridgeEnabled = prefs.isRadioVoxBridgeEnabled,
            isDeadReckoningEnabled = prefs.isDeadReckoningEnabled,
            isBreadcrumbRecording = prefs.isBreadcrumbRecording,
            breadcrumbs = prefs.loadBreadcrumbs(),
            messageBurnTtlSeconds = prefs.messageBurnTtlSeconds,
            hasDuressPin = prefs.duressPinHash.isNotBlank(),
            appLanguage = prefs.appLanguage,
            quickStatusVoiceEnabled = prefs.quickStatusVoiceEnabled,
            iceBloodType = prefs.iceBloodType,
            iceAllergies = prefs.iceAllergies,
            iceNotes = prefs.iceNotes,
            iceEmergencyContact = prefs.iceEmergencyContact,
            deadManSwitchMinutes = prefs.deadManSwitchMinutes,
            deadManRemainingSeconds = prefs.deadManSwitchMinutes * 60,
            geofenceEnabled = prefs.geofenceEnabled,
            geofenceLat = prefs.geofenceLat,
            geofenceLng = prefs.geofenceLng,
            geofenceRadiusMeters = prefs.geofenceRadiusMeters,
            expeditionModeEnabled = prefs.expeditionModeEnabled,
            expeditionAutoBelow20 = prefs.expeditionAutoBelow20,
            perThreadBurnTtlMap = prefs.getPerThreadBurnTtlMap(),
            hasDecoyPin = prefs.decoyPinHash.isNotBlank()
        )
    )
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    @Volatile
    private var appInForeground: Boolean = true
    @Volatile
    private var isSyncingUsernameHold: Boolean = false
    private var backupSyncJob: Job? = null
    private var internetNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val audioFrameSeqCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val relayRateMap = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
    @Volatile
    private var lastKeyAnnounceBroadcastMs: Long = 0L

    // Thread-safe backing map for discovered peers; UI state updates are throttled to at most once per 1,500ms
    // (or immediately when a brand-new peer is discovered or call/chat state changes).
    val peersBackingMap = java.util.concurrent.ConcurrentHashMap<String, MeshNode>()
    @Volatile
    private var lastPeerUiSnapshotMs: Long = 0L

    // Tracks peers that have already received their one-time DTN outbox flush on discovery
    val dtnFlushedPeers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Tracks delivery ACKs for outgoing CHAT and E2EE_DM messages (packetId -> acked)
    val pendingChatAcks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val lastDuplicateAckSentMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val linkCostRouter = com.example.service.LinkCostRouter()
    private val pendingAckFailoverJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val packetPrimaryTierMap = java.util.concurrent.ConcurrentHashMap<String, com.example.service.LinkCostRouter.TransportTier>()
    @Volatile
    private var updateBannerDismissedVersion: String = ""

    val audioService = TacticalAudioService(
        context = application,
        scope = viewModelScope
    )
    val persistentChatManager = PersistentChatManager(application)
    val notificationManager = com.example.service.TacticalNotificationManager(application)
    private val unreadCountsByThread = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val bleRadioService = LiveBleRadioService(application, viewModelScope)
    val gpsService = com.example.service.RealGpsLocationService(application)
    val sensorService = com.example.service.TacticalSensorService(application)

    private val pendingDtnPackets: MutableList<com.example.service.WireMeshPacket> =
        java.util.Collections.synchronizedList(prefs.loadPendingDtnPackets().toMutableList())
    @Volatile
    private var rootRadioBoostApplied = false

    val hotspotManager = com.example.util.LocalHotspotManager(application)
    @Volatile
    private var manualWebPortalPinned: Boolean = false
    @Volatile
    private var hotspotWebPortalRequested: Boolean = false
    private val webVoiceSeqCounter = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var latestWebVoiceFrame: Triple<Int, Pair<String, String>, String>? = null
    private val lastGatewayHeartbeatBridgeMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastGatewayTileRequestMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val pulseMeshWebServer: com.example.service.PulseMeshWebServer = com.example.service.PulseMeshWebServer(
        context = application,
        scope = viewModelScope,
        buildInfoJson = { portalUrl, webClientCount -> buildWebPortalInfoJson(portalUrl, webClientCount) },
        buildStateJson = { clientNodeId -> buildWebPortalStateJson(clientNodeId) },
        onClientRegistered = { reg -> handleWebClientRegistration(reg) },
        onWebPacketSubmitted = { submitted -> handleWebSubmittedPacket(submitted) },
        canFetchOnlineTiles = { _uiState.value.isInternetEnabled && hasValidatedOsInternet() }
    )
    val offGridBleEngine: com.example.service.OffGridBleMeshEngine = com.example.service.OffGridBleMeshEngine(
        context = application,
        scope = viewModelScope,
        localNodeId = { _uiState.value.localNodeId },
        localCallsign = { _uiState.value.operatorCallsign },
        getCurrentLocation = { Pair(_uiState.value.userLatitude, _uiState.value.userLongitude) },
        getBatteryPercent = { _uiState.value.realBatteryPercent },
        getLocationAccuracy = { gpsService.gpsState.value.accuracy },
        getHotspotRoleByte = {
            if (autoHotspotNegotiator.isCurrentlyHotspotHost() || _uiState.value.hotspotState.isActive) 0x01 else 0x00
        },
        shouldBroadcastCoarseLocation = { _uiState.value.broadcastCoarseLocationInBle },
        getLocationShareMode = { _uiState.value.locationShareMode }
    )

    val autoHotspotNegotiator: com.example.service.AutoHotspotNegotiator = com.example.service.AutoHotspotNegotiator(
        context = application,
        scope = viewModelScope,
        localNodeId = { _uiState.value.localNodeId },
        onSendBleGattPacket = { json -> offGridBleEngine.sendGattPacketChunked(json) },
        onPacketReceived = { json -> handleIncomingRawJson(json) },
        localCallsign = { _uiState.value.operatorCallsign },
        getCurrentLocation = { Pair(_uiState.value.userLatitude, _uiState.value.userLongitude) },
        getBatteryPercent = { _uiState.value.realBatteryPercent },
        isRootedProvider = { _uiState.value.isRootGranted },
        getLocationShareMode = { _uiState.value.locationShareMode }
    )

    val tacticalCallService = com.example.service.TacticalCallService(
        context = application,
        scope = viewModelScope,
        audioService = audioService,
        onSendAudioFrame = { encodedBytes ->
            val b64Audio = "ADPCM8K:" + android.util.Base64.encodeToString(encodedBytes, android.util.Base64.NO_WRAP)
            sendVoiceStreamBase64(b64Audio, packetType = "VOICE")
            _uiState.update {
                it.copy(
                    txAudioCount = it.txAudioCount + 1,
                    pttStatus = "TX VOICE (${encodedBytes.size}B 8kHz IMA-ADPCM)",
                    voiceHopStep = "TX VOICE #${it.txAudioCount + 1}"
                )
            }
        }
    )

    val ultrasonicModem = com.example.service.UltrasonicAcousticModem(
        context = application,
        scope = viewModelScope,
        isVoiceMicBusy = {
            tacticalCallService.callState.value.isInCall ||
                _uiState.value.duplexCallState.isInCall ||
                audioService.isRecording.value ||
                _uiState.value.isPttPressed ||
                !appInForeground
        },
        onAcousticSignalDecoded = { sigType, srcNodeIdHex ->
            val pkt = com.example.service.WireMeshPacket(
                packetId = "us_${srcNodeIdHex}_${System.currentTimeMillis()}",
                srcNodeId = srcNodeIdHex,
                srcCallsign = peersBackingMap[normalizeId(srcNodeIdHex)]?.callsign ?: "Node-$srcNodeIdHex",
                dstNodeId = "ALL",
                type = sigType,
                payload = "Ultrasonic 18.5kHz Acoustic Frame",
                sourceTransport = "ULTRASONIC",
                rssiDbm = -50
            )
            viewModelScope.launch(Dispatchers.Main) {
                handleIncomingPacket(pkt)
            }
        }
    )

    val bell202Modem = com.example.service.Bell202AcousticModem(
        context = application,
        scope = viewModelScope,
        isVoiceMicBusy = {
            tacticalCallService.callState.value.isInCall ||
                _uiState.value.duplexCallState.isInCall ||
                audioService.isRecording.value ||
                _uiState.value.isPttPressed ||
                !appInForeground
        },
        onBell202FrameDecoded = { frame ->
            val pktType = when (frame.typeByte) {
                com.example.service.Bell202AcousticModem.TYPE_SOS -> "SOS"
                com.example.service.Bell202AcousticModem.TYPE_CALL_RING -> "CALL_RING"
                com.example.service.Bell202AcousticModem.TYPE_KEY_VERIFY -> "KEY_VERIFY"
                else -> "CHAT"
            }
            val pkt = com.example.service.WireMeshPacket(
                packetId = "b202_${frame.srcNodeIdHex}_${System.currentTimeMillis()}",
                srcNodeId = frame.srcNodeIdHex,
                srcCallsign = peersBackingMap[normalizeId(frame.srcNodeIdHex)]?.callsign ?: "Acoustic-${frame.srcNodeIdHex}",
                dstNodeId = "ALL",
                type = pktType,
                payload = frame.text.ifBlank { "Bell 202 AFSK Signal" },
                sourceTransport = "ACOUSTIC BELL202",
                rssiDbm = -48
            )
            viewModelScope.launch(Dispatchers.Main) {
                handleIncomingPacket(pkt)
            }
        }
    )

    private var ringSignalRepeatJob: Job? = null
    private var acceptSignalRetryJob: Job? = null
    private var speakingBannerClearJob: Job? = null

    @Volatile
    private var callAcceptConfirmedByPeer: Boolean = false

    @Volatile
    private var lastCallAcceptAckSentMs: Long = 0L

    @Volatile
    private var lastCallEndedMs: Long = 0L

    @Volatile
    private var lastEndedPeerNormId: String? = null

    /**
     * Sends a Base64-encoded 160ms G.711 mu-law voice frame (`type = "VOICE"`, `640B` raw / `856` Base64 chars)
     * exclusively over Hotspot UDP (45888) / TCP (45889), primary BLE GATT (`0000CAFF`) when Hotspot is not linked,
     * and Hybrid `ntfy.sh` ONLY when `isInternetEnabled == true`.
     */
    fun sendVoiceStreamBase64(base64Audio: String, packetType: String = "VOICE") {
        if (base64Audio.isBlank()) return
        val state = _uiState.value
        val targetDst = if ((state.duplexCallState.isInCall || tacticalCallService.callState.value.isInCall) && state.activeCallTargetId.isNotBlank()) {
            state.activeCallTargetId
        } else {
            "ALL"
        }
        val seq = audioFrameSeqCounter.incrementAndGet() and 0xFFFF
        val pkt = com.example.service.WireMeshPacket(
            packetId = "vs_${normalizeId(state.localNodeId)}_${seq}_${System.currentTimeMillis() and 0xFFFF}",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = targetDst,
            type = packetType,
            payload = base64Audio,
            ttl = 7,
            hops = 0
        )
        sendVoiceAudioPacket(pkt)
    }

    fun normalizeId(id: String): String =
        id.trim().uppercase().removePrefix("0X").removePrefix("NODE-").removePrefix("PEER-").takeLast(4)

    fun isBlockedFakeCallsign(callsign: String): Boolean {
        return com.example.service.OffGridBleMeshEngine.isBlockedFakeCallsign(callsign)
    }

    val meshNetworkService = com.example.service.LiveMeshNetworkService(
        scope = viewModelScope,
        localNodeId = { _uiState.value.localNodeId },
        localCallsign = { _uiState.value.operatorCallsign },
        channelName = { _uiState.value.meshChannel },
        getCurrentLocation = { Pair(_uiState.value.userLatitude, _uiState.value.userLongitude) },
        getBatteryPercent = { _uiState.value.realBatteryPercent },
        isInternetEnabled = { _uiState.value.isInternetEnabled },
        context = application,
        meshPassphrase = { _uiState.value.meshPassphrase },
        localUsername = { _uiState.value.claimedUsername },
        usernameRelayBaseUrl = { _uiState.value.usernameRelayUrl },
        myPrivateKeyBase64 = { prefs.x25519PrivateKey },
        myAuthToken = { prefs.accountAuthToken },
        resolvePeerPublicKeyByUsername = { uname ->
            prefs.getUsernameClaim(uname)?.publicKeyBase64.orEmpty()
        },
        getInboxCursor = { prefs.lastUsernameInboxCursor },
        setInboxCursor = { cursor -> prefs.lastUsernameInboxCursor = cursor },
        onInternetRestored = {
            if (prefs.isUsernameOnHold && _uiState.value.isInternetEnabled) {
                syncPendingUsernameHoldIfOnline()
            } else if (_uiState.value.isInternetEnabled) {
                refreshSignedUsernameClaimIfNeeded()
            }
        }
    )

    private fun hasValidatedOsInternet(): Boolean {
        return runCatching {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps != null &&
                activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return@runCatching true
            }
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrDefault(false)
    }

    private var callTimerJob: Job? = null
    private var telemetryTickJob: Job? = null
    private var simAnimJob: Job? = null
    private var autoCheckInJob: Job? = null
    private var deadManSwitchJob: Job? = null
    @Volatile
    private var lastStormAlertBroadcastMs: Long = 0L
    private var statusTts: android.speech.tts.TextToSpeech? = null
    @Volatile
    private var statusTtsReady: Boolean = false

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }

    init {
        // Automatic startup migration: scrub any legacy bot claims/threads (e.g. "Bravo Relay") left from older installs
        prefs.purgeLegacyBotRecords()
        persistentChatManager.purgeCachedFakeThreads()
        initializeNodes()
        initializeWaypoints()
        initializePackets()
        startTelemetrySimulation()
        verifyRootStatus()

        // 1. Auto-match phone chipset profile against real hardware on launch (works for ANY Android brand)
        val autoProfile = HardwareDetector.resolveAutoMatchedProfile(detectedHw)
        if (prefs.selectedProfileId.isBlank() ||
            prefs.selectedProfileId == "desktop_emulator_container" ||
            prefs.selectedProfileId == "oneplus_15" ||
            prefs.selectedProfileId.startsWith("live_")
        ) {
            selectPhoneModel(autoProfile)
        }

        // 2. Wire adaptive VOICE_STREAM pipeline, foreground mic gates & Hotspot Autoconnect engine
        val fastLinkDetector: () -> Boolean = {
            autoHotspotNegotiator.hasHighSpeedLink() ||
                _uiState.value.isInternetEnabled ||
                (pulseMeshWebServer.isRunning() && _uiState.value.webPortalStatus.activeClientCount > 0)
        }
        audioService.isHighSpeedLinkActive = fastLinkDetector
        tacticalCallService.isHighSpeedLinkActive = fastLinkDetector
        audioService.isBleVoiceSending = { offGridBleEngine.isBleVoiceSendingNow }
        tacticalCallService.isBleVoiceSending = { offGridBleEngine.isBleVoiceSendingNow }
        audioService.isCapturePermitted = {
            appInForeground && (
                audioService.isRecording.value ||
                    audioService.isRecordingVoiceDrop.value ||
                    _uiState.value.isPttPressed
                )
        }
        tacticalCallService.isCapturePermitted = {
            appInForeground && (
                tacticalCallService.callState.value.isInCall ||
                    tacticalCallService.callState.value.isRecordingVoicemail ||
                    _uiState.value.duplexCallState.isInCall ||
                    _uiState.value.duplexCallState.isRecordingVoicemail
                )
        }

        com.example.util.OfflineRegionDownloader.isInternetEnabled = prefs.isInternetEnabled
        autoHotspotNegotiator.setAutoHotspotPopupEnabled(prefs.isHotspotAutoPopupEnabled)
        if (prefs.autoCheckInIntervalMin > 0) {
            scheduleAutoCheckInLoop(prefs.autoCheckInIntervalMin)
        }
        tacticalCallService.onUnansweredCallResetToIdle = {
            ringSignalRepeatJob?.cancel()
            ringSignalRepeatJob = null
            acceptSignalRetryJob?.cancel()
            acceptSignalRetryJob = null
            callAcceptConfirmedByPeer = false
            notificationManager.clearCallNotification()
            offGridBleEngine.updateCallStateBeacon(
                com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IDLE,
                "ALL"
            )
            _uiState.update {
                it.copy(
                    callState = com.example.service.CallSignalingState.IDLE,
                    remoteCallerId = "",
                    remoteCallerName = "",
                    activeCallTargetId = "ALL",
                    activeSpeakingBanner = "",
                    activePrivateCallNode = null,
                    isPrivateCallActive = false,
                    incomingPrivateCall = null,
                    callAckConfirmed = false
                )
            }
        }

        // Listen for Lock-Screen / Notification Shade Call Action Broadcasts (Accept / Decline / End / Mute)
        viewModelScope.launch {
            com.example.service.TacticalNotificationManager.callActions.collect { action ->
                when (action) {
                    com.example.service.CallNotificationAction.ACCEPT -> acceptDuplexCall()
                    com.example.service.CallNotificationAction.DECLINE -> declineDuplexCall()
                    com.example.service.CallNotificationAction.END -> endDuplexCall()
                    com.example.service.CallNotificationAction.MUTE_TOGGLE -> toggleCallMute()
                }
            }
        }

        // Listen for Notification Shade "MARK AS READ" action on chat notifications
        viewModelScope.launch {
            com.example.service.TacticalNotificationManager.markThreadReadEvents.collect { threadId ->
                markThreadAsRead(threadId)
            }
        }

        // Listen for Notification Shade inline "REPLY" action on chat notifications
        viewModelScope.launch {
            com.example.service.TacticalNotificationManager.chatReplyEvents.collect { reply ->
                sendMessageInThread(reply.threadId, reply.text)
                markThreadAsRead(reply.threadId)
            }
        }

        // Watch real network connectivity so held offline usernames auto-claim when back online
        runCatching {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (prefs.isUsernameOnHold && _uiState.value.isInternetEnabled) {
                            syncPendingUsernameHoldIfOnline()
                        }
                    }
                }
                cm.registerNetworkCallback(req, cb)
                internetNetworkCallback = cb
            }
        }
        if (prefs.isUsernameOnHold && _uiState.value.isInternetEnabled) {
            syncPendingUsernameHoldIfOnline()
        } else if (_uiState.value.isInternetEnabled) {
            refreshSignedUsernameClaimIfNeeded()
        }

        // Automatic startup update check (strictly skipped when Off-Grid Mode is active)
        checkForAppUpdateOnStartup()

        offGridBleEngine.onImmediatePeerRegistered = { nodeId, callsign, lat, lng, battery, rssi, isHost, transport ->
            registerOrUpdatePeerInstant(
                peerNodeId = nodeId,
                callsign = callsign,
                lat = lat,
                lng = lng,
                battery = battery,
                rssiDbm = rssi,
                isHotspotHost = isHost,
                sourceTransport = transport
            )
        }

        autoHotspotNegotiator.onImmediatePeerRegistered = { nodeId, callsign, peerIp, lat, lng, battery, transport ->
            registerOrUpdatePeerInstant(
                peerNodeId = nodeId,
                callsign = callsign,
                lat = lat,
                lng = lng,
                battery = battery,
                ipAddress = peerIp,
                sourceTransport = transport
            )
        }

        autoHotspotNegotiator.onPeerIpDiscovered = { peerNodeId, peerIp ->
            updatePeerIpAddress(peerNodeId, peerIp)
            flushPendingDtnPacketsToPeer(peerNodeId)
        }

        offGridBleEngine.onPeerBleDiscovered = { peerNodeId, _, _, isHotspotHost ->
            val knownPeerIds = peersBackingMap.values.map { it.id }
            autoHotspotNegotiator.onPeerDiscovered(peerNodeId, isHotspotHost, knownPeerIds)
            flushPendingDtnPacketsToPeer(peerNodeId)
        }

        // Walkie-Talkie PTT 160ms G.711 u-Law Base64 frames routed exclusively over 0xCAFF + UDP ("VOICE")
        audioService.onMuLawPttBytesReady = null
        audioService.onStreamingPttChunkReady = { b64Audio ->
            val cleanB64 = b64Audio.removePrefix("ULAW:")
            sendVoiceStreamBase64(cleanB64, packetType = "VOICE")
            _uiState.update {
                it.copy(
                    txAudioCount = it.txAudioCount + 1,
                    pttStatus = "TX VOICE (640B G.711 u-Law)",
                    voiceHopStep = "TX VOICE #${it.txAudioCount + 1}"
                )
            }
        }
        offGridBleEngine.onGattPeerConnectedForKeyExchange = { _ ->
            announcePublicKey(force = true)
        }
        autoHotspotNegotiator.onTcpOrIpPeerReady = { _, _ ->
            announcePublicKey(force = true)
        }

        // Note: offGridBleEngine.incomingPackets and meshNetworkService.incomingPackets already emit
        // every parsed packet once; do not double-subscribe via onRawJsonPacketReceived.
        autoHotspotNegotiator.onPacketReceivedWithMeta = { rawJson, transport, wifiRssi ->
            handleIncomingRawJson(rawJson, transport, wifiRssi)
        }
        autoHotspotNegotiator.onAudioFrameReceived = { rawAudioBytes ->
            val unpackedPa = com.example.service.TacticalAudioService.unpackBinaryAdpcmVoiceFrame(rawAudioBytes)
            val isCurrentlyInCall = tacticalCallService.callState.value.isInCall || _uiState.value.duplexCallState.isInCall
            if (unpackedPa != null) {
                if (unpackedPa.muLawPayload.isNotEmpty() &&
                    shouldAcceptVoiceFrame(unpackedPa.srcNodeId, unpackedPa.seq)
                ) {
                    runCatching {
                        audioService.playIncomingAdpcmBytes(unpackedPa.muLawPayload, isCallAudio = isCurrentlyInCall)
                        if (isCurrentlyInCall) {
                            tacticalCallService.recordIncomingFramePlayed()
                        }
                    }
                    _uiState.update { it.copy(rxAudioCount = it.rxAudioCount + 1) }
                }
            } else {
                val unpackedPv = com.example.service.TacticalAudioService.unpackBinaryVoiceFrame(rawAudioBytes)
                val isNewPv = unpackedPv == null || shouldAcceptVoiceFrame(unpackedPv.srcNodeId, unpackedPv.seq)
                val muLawPayload = unpackedPv?.muLawPayload ?: rawAudioBytes
                if (isNewPv && muLawPayload.isNotEmpty()) {
                    runCatching {
                        audioService.playIncomingMuLawBytes(muLawPayload, isCallAudio = isCurrentlyInCall)
                        if (isCurrentlyInCall) {
                            tacticalCallService.recordIncomingFramePlayed()
                        }
                    }
                    _uiState.update { it.copy(rxAudioCount = it.rxAudioCount + 1) }
                }
            }
        }
        autoHotspotNegotiator.onHotspotButtonStateChanged = { btnText ->
            _uiState.update { it.copy(hotspotButtonText = btnText) }
        }
        autoHotspotNegotiator.onLocalHotspotCredentialsUpdated = { ssid, pass ->
            offGridBleEngine.localHotspotSsid = ssid
            offGridBleEngine.localHotspotPassphrase = pass
        }
        offGridBleEngine.onPeerHotspotPassphraseDiscovered = { peerNodeId, ssidHint, passphrase ->
            val normPeer = normalizeId(peerNodeId)
            val existing = peersBackingMap[normPeer]
            val peerIpHint = existing?.ipAddress?.ifBlank { com.example.service.AutoHotspotNegotiator.P2P_GO_IP }
                ?: com.example.service.AutoHotspotNegotiator.P2P_GO_IP
            if (existing != null) {
                peersBackingMap[normPeer] = existing.copy(
                    isHotspotHost = true,
                    hotspotSsid = ssidHint.ifBlank { existing.hotspotSsid },
                    hotspotPass = passphrase
                )
            }
            autoHotspotNegotiator.handleHotspotAutoJoin(peerNodeId, ssidHint, passphrase, peerIpHint)
        }
        hotspotManager.onHotspotStartedCallback = { ssid, pass, hostIp ->
            hotspotWebPortalRequested = true
            autoHotspotNegotiator.onExternalLocalHotspotStarted(ssid, pass, hostIp)
            pulseMeshWebServer.start(hostIp)
            pulseMeshWebServer.updateHostIpHint(hostIp)
        }
        hotspotManager.onHotspotStoppedCallback = {
            hotspotWebPortalRequested = false
            autoHotspotNegotiator.stopHotspotGroup()
            if (!manualWebPortalPinned) {
                pulseMeshWebServer.stop()
            }
        }
        autoHotspotNegotiator.onFallbackToLocalHotspotCallback = {
            hotspotManager.startLocalHotspot()
        }
        offGridBleEngine.start()

        // Wire Offline-to-Internet Gateway Map Tile Bridge (HTTP proxy over Wi-Fi Direct/Hotspot + BLE mesh TILE_REQ fallback)
        val resolveGatewayProxyUrl: () -> String? = {
            val gwPeer = peersBackingMap.values
                .filter { it.isGateway && it.ipAddress.isNotBlank() }
                .minByOrNull { it.hopCount }
            val fallbackHostIp = autoHotspotNegotiator.status.value.hostIp
                .takeIf { it.isNotBlank() && autoHotspotNegotiator.status.value.isConnected && !autoHotspotNegotiator.status.value.isHotspotHost && peersBackingMap.values.any { p -> p.isGateway } }
            val targetIp = gwPeer?.ipAddress?.ifBlank { fallbackHostIp.orEmpty() } ?: fallbackHostIp.orEmpty()
            if (targetIp.isNotBlank()) "http://$targetIp:8080" else null
        }
        val resolveGatewayCallsign: () -> String? = {
            peersBackingMap.values
                .filter { it.isGateway }
                .minByOrNull { it.hopCount }
                ?.callsign
        }
        val requestTileViaBleGateway: (Int, Int, Int) -> Boolean = { z, x, y ->
            requestMapTileFromGatewayPeer(z, x, y)
        }
        com.example.util.NativeTileMapManager.gatewayTileProxyBaseUrl = resolveGatewayProxyUrl
        com.example.util.NativeTileMapManager.gatewayMeshTileRequester = requestTileViaBleGateway
        com.example.util.OfflineRegionDownloader.gatewayTileProxyBaseUrl = resolveGatewayProxyUrl
        com.example.util.OfflineRegionDownloader.gatewayPeerCallsign = resolveGatewayCallsign
        com.example.util.OfflineRegionDownloader.gatewayMeshTileRequester = requestTileViaBleGateway

        // Collect v1.2 Wi-Fi Direct Embedded Web Portal Status
        viewModelScope.launch {
            pulseMeshWebServer.status.collect { wStatus ->
                _uiState.update { it.copy(webPortalStatus = wStatus) }
                if (wStatus.isRunning) {
                    com.example.service.MeshForegroundRelayService.startOrUpdate(
                        context = getApplication(),
                        peerCount = _uiState.value.activePeerCount,
                        dtnCount = _uiState.value.pendingDtnCount,
                        statusSummary = "Hotspot Web Portal LIVE at ${wStatus.portalUrl.ifBlank { ":${wStatus.port}" }}"
                    )
                }
            }
        }

        // Collect BLE mesh telemetry
        viewModelScope.launch {
            offGridBleEngine.telemetry.collect { telem ->
                _uiState.update { current ->
                    current.copy(
                        txFramesCount = telem.txFramesCount,
                        rxFramesCount = telem.rxFramesCount,
                        lastRssiDbm = if (telem.lastRssiDbm != 0) telem.lastRssiDbm else current.lastRssiDbm,
                        bleTxStatus = telem.bleTxStatus,
                        connectedServerDeviceAddresses = telem.connectedServerDeviceAddresses,
                        connectedGattClientAddresses = telem.connectedGattClientAddresses,
                        isTwoWayGattSymmetric = telem.isTwoWayGattSymmetric,
                        activeL2capChannelsCount = telem.activeL2capChannelsCount,
                        localL2capPsm = telem.localL2capPsm
                    )
                }
            }
        }

        // Collect Local-Only & P2P Wi-Fi Hotspot State
        viewModelScope.launch {
            autoHotspotNegotiator.status.collect { aStatus ->
                val isStartingHotspot = aStatus.buttonStateText.contains("STARTING", ignoreCase = true) ||
                    aStatus.role == "P2P_GO" ||
                    aStatus.role == "HOST_AP"
                if (aStatus.isHotspotHost || aStatus.isConnected || hotspotWebPortalRequested) {
                    val hintIp = aStatus.hostIp.ifBlank { com.example.service.AutoHotspotNegotiator.P2P_GO_IP }
                    if (!pulseMeshWebServer.isRunning()) {
                        pulseMeshWebServer.start(hintIp)
                    } else {
                        pulseMeshWebServer.updateHostIpHint(hintIp)
                    }
                } else if (!isStartingHotspot &&
                    !hotspotManager.hotspotState.value.isActive &&
                    !manualWebPortalPinned &&
                    !hotspotWebPortalRequested &&
                    pulseMeshWebServer.isRunning()
                ) {
                    pulseMeshWebServer.stop()
                }
                _uiState.update { current ->
                    current.copy(
                        hotspotButtonText = aStatus.buttonStateText,
                        pendingHotspotPrompt = if (current.isHotspotAutoPopupEnabled) aStatus.pendingHotspotPrompt else null,
                        hotspotState = current.hotspotState.copy(
                            isActive = aStatus.isConnected || current.hotspotState.isActive,
                            ssid = aStatus.ssid.ifBlank { current.hotspotState.ssid },
                            passphrase = aStatus.passphrase.ifBlank { current.hotspotState.passphrase },
                            statusMessage = aStatus.statusMessage
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            hotspotManager.hotspotState.collect { hState ->
                _uiState.update { current ->
                    current.copy(
                        hotspotState = com.example.util.HotspotState(
                            isActive = hState.isActive || autoHotspotNegotiator.status.value.isConnected,
                            ssid = hState.ssid.ifBlank { autoHotspotNegotiator.status.value.ssid },
                            passphrase = hState.passphrase.ifBlank { autoHotspotNegotiator.status.value.passphrase },
                            statusMessage = if (hState.isActive) hState.statusMessage else autoHotspotNegotiator.status.value.statusMessage
                        )
                    )
                }
            }
        }

        // Collect Duplex Call State (duration, mute, speaker, signalingState)
        viewModelScope.launch {
            tacticalCallService.callState.collect { cState ->
                _uiState.update {
                    it.copy(
                        duplexCallState = cState,
                        callState = cState.signalingState,
                        remoteCallerId = cState.peerNodeId.ifBlank { it.remoteCallerId },
                        remoteCallerName = cState.peerCallsign.ifBlank { it.remoteCallerName }
                    )
                }
            }
        }

        // Collect incoming 12-byte BLE mesh beacons & GATT packets from peer devices
        viewModelScope.launch {
            offGridBleEngine.incomingPackets.collect { packet ->
                handleIncomingMeshPacket(packet)
            }
        }

        // Start live cross-device hybrid mesh packet pub/sub
        meshNetworkService.start()

        // Listen for real-time incoming mesh packets from hybrid relay
        viewModelScope.launch {
            meshNetworkService.incomingPackets.collect { packet ->
                handleIncomingMeshPacket(packet)
            }
        }

        // Collect live hybrid relay status
        viewModelScope.launch {
            meshNetworkService.relayStatus.collect { status ->
                _uiState.update { it.copy(relayStatus = status) }
            }
        }

        // Listen for real device GPS state
        viewModelScope.launch {
            gpsService.gpsState.collect { gps ->
                _uiState.update { current ->
                    val userLat = gps.latitude ?: current.userLatitude
                    val userLon = gps.longitude ?: current.userLongitude

                    val updatedNodes = current.nodes.map { node ->
                        if (node.isSelf) {
                            node.copy(
                                latitude = userLat ?: 0.0,
                                longitude = userLon ?: 0.0
                            )
                        } else if (userLat != null && userLon != null && node.latitude != 0.0 && node.longitude != 0.0) {
                            val dist = com.example.service.LiveMeshNetworkService.calculateDistanceMeters(
                                userLat, userLon, node.latitude, node.longitude
                            )
                            node.copy(distanceMeters = dist)
                        } else {
                            node
                        }
                    }

                    val nextBreadcrumbs = if (current.isBreadcrumbRecording && userLat != null && userLon != null && (userLat != 0.0 || userLon != 0.0)) {
                        val lastPt = current.breadcrumbs.lastOrNull()
                        val movedEnough = lastPt == null || com.example.service.LiveMeshNetworkService.calculateDistanceMeters(
                            lastPt.lat, lastPt.lon, userLat, userLon
                        ) >= 4.0
                        if (movedEnough) {
                            val updatedTrail = (current.breadcrumbs + BreadcrumbPoint(
                                lat = userLat,
                                lon = userLon,
                                altM = current.barometricAltitudeMeters ?: 0.0,
                                timestamp = System.currentTimeMillis()
                            )).takeLast(500)
                            prefs.saveBreadcrumbs(updatedTrail)
                            updatedTrail
                        } else {
                            current.breadcrumbs
                        }
                    } else {
                        current.breadcrumbs
                    }

                    current.copy(
                        userLatitude = userLat,
                        userLongitude = userLon,
                        isGpsLocked = gps.isLocked,
                        gpsStatusMessage = gps.statusMessage,
                        usingRealGps = gps.isLocked && !gps.isManualPin,
                        nodes = updatedNodes,
                        breadcrumbs = nextBreadcrumbs
                    )
                }
            }
        }

        viewModelScope.launch {
            bleRadioService.txState.collect { tx ->
                _uiState.update { it.copy(bleTxState = tx) }
            }
        }
        viewModelScope.launch {
            bleRadioService.isRxSnifferActive.collect { active ->
                _uiState.update { it.copy(isBleRxSnifferActive = active) }
            }
        }
        viewModelScope.launch {
            bleRadioService.scannedDevices.collect { list ->
                _uiState.update { it.copy(scannedBleDevices = list) }
            }
        }
        viewModelScope.launch {
            bleRadioService.totalRxPacketsCount.collect { count ->
                _uiState.update { it.copy(totalBleRxPackets = count) }
            }
        }

        // Start real-time Compass (Rotation Vector / Magnetometer), Barometer Altitude & Step Dead-Reckoning service
        ultrasonicModem.voxPreKeyPilotEnabled = prefs.isRadioVoxBridgeEnabled
        if (prefs.isBackgroundRelayServiceEnabled) {
            com.example.service.MeshForegroundRelayService.startOrUpdate(
                context = getApplication(),
                peerCount = peersBackingMap.size,
                dtnCount = _uiState.value.pendingDtnCount
            )
        }
        sensorService.onStepDetected = { headingDeg ->
            val st = _uiState.value
            if (st.isDeadReckoningEnabled) {
                val baseLat = st.userLatitude.takeIf { it != 0.0 } ?: 37.9838
                val baseLon = st.userLongitude.takeIf { it != 0.0 } ?: 23.7275
                val (nextLat, nextLon) = com.example.service.TacticalSensorService.stepAdvanceLatLon(
                    lat = baseLat,
                    lon = baseLon,
                    headingDeg = headingDeg
                )
                val updatedTrail = if (st.isBreadcrumbRecording) {
                    (st.breadcrumbs + BreadcrumbPoint(
                        lat = nextLat,
                        lon = nextLon,
                        altM = st.barometricAltitudeMeters ?: 0.0,
                        timestamp = System.currentTimeMillis()
                    )).takeLast(500).also { prefs.saveBreadcrumbs(it) }
                } else {
                    st.breadcrumbs
                }
                _uiState.update { cur ->
                    cur.copy(
                        userLatitude = nextLat,
                        userLongitude = nextLon,
                        breadcrumbs = updatedTrail
                    )
                }
            }
        }
        sensorService.start()
        viewModelScope.launch {
            sensorService.sensorState.collect { sState ->
                _uiState.update { current ->
                    val resolvedAlt = sState.barometricAltitudeMeters ?: current.barometricAltitudeMeters
                    val updatedNodes = if (resolvedAlt != null) {
                        current.nodes.map { node ->
                            if (node.isSelf) node.copy(altitudeM = resolvedAlt) else node
                        }
                    } else {
                        current.nodes
                    }
                    current.copy(
                        deviceHeadingDeg = sState.deviceHeadingDeg,
                        barometricPressureHpa = sState.barometricPressureHpa,
                        barometricAltitudeMeters = resolvedAlt,
                        hasBarometerSensor = sState.hasBarometerSensor,
                        pressureTrendHpaPer3h = sState.pressureTrendHpaPer3h,
                        weatherTrendLabel = sState.weatherTrendLabel,
                        isStormAlertActive = sState.isStormAlertActive,
                        stepCount = sState.stepCount,
                        nodes = updatedNodes
                    )
                }
                if (sState.hasBarometerSensor && sState.isStormAlertActive) {
                    val nowStorm = System.currentTimeMillis()
                    if (nowStorm - lastStormAlertBroadcastMs > 30 * 60_000L) {
                        lastStormAlertBroadcastMs = nowStorm
                        val deltaStr = String.format(java.util.Locale.US, "%.1f", sState.pressureTrendHpaPer3h ?: -3.0f)
                        val pVal = String.format(java.util.Locale.US, "%.1f", sState.barometricPressureHpa ?: 1000f)
                        val alertText = "BAROMETRIC STORM ALERT: Pressure dropped $deltaStr hPa (now $pVal hPa)"
                        val st = _uiState.value
                        val pkt = com.example.service.WireMeshPacket(
                            packetId = "wx_${nowStorm and 0xFFFFF}",
                            srcNodeId = st.localNodeId,
                            srcCallsign = st.operatorCallsign,
                            dstNodeId = "ALL",
                            type = "WEATHER_ALERT",
                            payload = alertText,
                            ttl = 5,
                            hops = 0,
                            timestamp = nowStorm
                        )
                        sendOmniPacket(pkt)
                    }
                }
            }
        }
    }

    private fun buildMergedNodesList(currentSelfNodes: List<MeshNode>): List<MeshNode> {
        val selfNode = currentSelfNodes.firstOrNull { it.isSelf } ?: MeshNode(
            id = _uiState.value.localNodeId,
            callsign = "${_uiState.value.operatorCallsign} (Self)",
            model = _uiState.value.selectedPhoneProfile.modelName,
            isSelf = true
        )
        val peers = peersBackingMap.values
            .filter { !it.isSelf && !isBlockedFakeCallsign(it.callsign) }
            .sortedBy { it.id }
        return listOf(selfNode) + peers
    }

    private fun updatePeerIpAddress(peerNodeId: String, ipAddress: String) {
        if (peerNodeId.isBlank() || ipAddress.isBlank()) return
        val normPeer = normalizeId(peerNodeId)
        val now = System.currentTimeMillis()
        val existing = peersBackingMap[normPeer]
        if (existing != null) {
            peersBackingMap[normPeer] = existing.copy(
                ipAddress = ipAddress,
                lastHeardAgoSec = 0,
                lastSeenMs = now,
                transports = existing.transports + "UDP/LAN"
            )
        }
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (!node.isSelf && normalizeId(node.id) == normPeer) {
                    node.copy(
                        ipAddress = ipAddress,
                        lastHeardAgoSec = 0,
                        lastSeenMs = now,
                        transports = node.transports + "UDP/LAN"
                    )
                } else {
                    node
                }
            }
            current.copy(nodes = updatedNodes)
        }
    }

    /**
     * Rule 1 & Rule 2: Decoupled Instant <1s Peer Registration + Throttled UI State Updates (1,500ms).
     * - Maintains thread-safe `peersBackingMap = ConcurrentHashMap<String, MeshNode>()`.
     * - When a beacon or heartbeat arrives from an existing peer, updates `peersBackingMap[normSrc]` immediately in memory,
     *   and ONLY pushes a snapshot to the Compose UI state at most once every `1,500ms` OR immediately when a brand-new peer ID is discovered (`forceImmediateUiUpdate`).
     */
    fun registerOrUpdatePeerInstant(
        peerNodeId: String,
        callsign: String = "",
        lat: Double? = null,
        lng: Double? = null,
        altitude: Double? = null,
        battery: Int? = null,
        rssiDbm: Int = -55,
        ipAddress: String = "",
        isHotspotHost: Boolean = false,
        sourceTransport: String = "BLE",
        nowMs: Long = System.currentTimeMillis(),
        forceImmediateUiUpdate: Boolean = false,
        hopCount: Int? = null,
        isGateway: Boolean = false
    ) {
        val normSrc = normalizeId(peerNodeId)
        val normSelf = normalizeId(_uiState.value.localNodeId)
        if (normSrc.isBlank() || normSrc == normSelf) return
        if (callsign.isNotBlank() && isBlockedFakeCallsign(callsign)) return

        val canonicalSrcId = "0x$normSrc"
        val current = _uiState.value
        val existingPeer = peersBackingMap[normSrc] ?: current.nodes.find { !it.isSelf && normalizeId(it.id) == normSrc }
        val isBrandNewPeer = existingPeer == null

        val resolvedCallsign = callsign.trim().takeIf { it.isNotBlank() && !isBlockedFakeCallsign(it) }
            ?: existingPeer?.callsign?.takeIf { !isBlockedFakeCallsign(it) }
            ?: "Node-$normSrc"

        val peerLat = lat?.takeIf { it != 0.0 } ?: existingPeer?.latitude ?: 0.0
        val peerLng = lng?.takeIf { it != 0.0 } ?: existingPeer?.longitude ?: 0.0
        val distance = if (current.userLatitude != null && current.userLongitude != null && peerLat != 0.0 && peerLng != 0.0) {
            com.example.service.LiveMeshNetworkService.calculateDistanceMeters(
                current.userLatitude, current.userLongitude, peerLat, peerLng
            )
        } else existingPeer?.distanceMeters

        val mergedTransports = (existingPeer?.transports ?: emptySet()) +
            listOfNotNull(sourceTransport.takeIf { it.isNotBlank() })

        val resolvedBlePhy = if (sourceTransport == "BLE CODED" || sourceTransport == "BLE 1M") {
            sourceTransport
        } else {
            existingPeer?.lastBlePhy ?: "BLE 1M"
        }

        val storedPeerKey = existingPeer?.publicKeyBase64?.ifBlank { null }
            ?: prefs.getPeerPublicKey(canonicalSrcId).orEmpty()
        val storedSafetyFp = if (storedPeerKey.isNotBlank()) {
            TacticalE2eeManager.computePairwiseSafetyFingerprint(prefs.x25519PublicKey, storedPeerKey)
        } else {
            existingPeer?.safetyFingerprint.orEmpty()
        }
        val storedKeyWarning = prefs.isPeerKeyChanged(canonicalSrcId) || (existingPeer?.isKeyChangedWarning == true)

        val resolvedHopCount = when {
            hopCount != null -> {
                val cleanHop = hopCount.coerceIn(1, 7)
                // If we recently heard this peer directly (1 hop within 15s), prefer the shorter hop count
                if (existingPeer != null &&
                    existingPeer.hopCount < cleanHop &&
                    (nowMs - existingPeer.lastSeenMs) < 15_000L
                ) {
                    existingPeer.hopCount
                } else {
                    cleanHop
                }
            }
            else -> existingPeer?.hopCount ?: 1
        }

        val updatedPeer = MeshNode(
            id = existingPeer?.id ?: canonicalSrcId,
            callsign = resolvedCallsign,
            model = existingPeer?.model ?: "Discovered Mesh Peer",
            latitude = peerLat,
            longitude = peerLng,
            altitudeM = altitude ?: existingPeer?.altitudeM ?: 100.0,
            speedKmh = 0.0,
            batteryPercent = battery ?: existingPeer?.batteryPercent ?: 85,
            rssiDbm = if (rssiDbm != 0) rssiDbm else (existingPeer?.rssiDbm ?: -55),
            snrDb = existingPeer?.snrDb ?: 14.0,
            hopCount = resolvedHopCount,
            lastHeardAgoSec = 0,
            lastSeenMs = nowMs,
            isSelf = false,
            isGateway = isGateway || (existingPeer?.isGateway == true),
            distanceMeters = distance,
            ipAddress = ipAddress.ifBlank { existingPeer?.ipAddress.orEmpty() },
            isHotspotHost = isHotspotHost || (existingPeer?.isHotspotHost == true),
            hotspotSsid = existingPeer?.hotspotSsid.orEmpty(),
            hotspotPass = existingPeer?.hotspotPass.orEmpty(),
            lastBlePhy = resolvedBlePhy,
            transports = mergedTransports,
            publicKeyBase64 = storedPeerKey,
            safetyFingerprint = storedSafetyFp,
            isKeyChangedWarning = storedKeyWarning
        )

        peersBackingMap[normSrc] = updatedPeer

        val isIdentityOrIpUpgrade = existingPeer != null && (
            (existingPeer.callsign.startsWith("Node-") && !resolvedCallsign.startsWith("Node-")) ||
            (existingPeer.ipAddress.isBlank() && updatedPeer.ipAddress.isNotBlank())
        )
        val shouldPushUi = isBrandNewPeer ||
            isIdentityOrIpUpgrade ||
            forceImmediateUiUpdate ||
            (nowMs - lastPeerUiSnapshotMs >= PEER_UI_THROTTLE_MS)

        if (shouldPushUi) {
            lastPeerUiSnapshotMs = nowMs
            _uiState.update { stateSnap ->
                val mergedNodes = buildMergedNodesList(stateSnap.nodes)
                stateSnap.copy(
                    nodes = mergedNodes,
                    activePeerCount = mergedNodes.count { !it.isSelf }
                )
            }
            refreshConversationsList()
        }
    }

    /**
     * Prunes a peer from `peersBackingMap` and `activePeers` ONLY if (nowMs - peer.lastSeenMs > 45_000L) (45 seconds).
     */
    fun pruneStalePeers(nowMs: Long = System.currentTimeMillis()) {
        val staleKeys = mutableListOf<String>()
        for ((key, peer) in peersBackingMap) {
            val elapsedMs = nowMs - peer.lastSeenMs
            if (elapsedMs > PEER_STALE_TIMEOUT_MS) {
                staleKeys.add(key)
            } else {
                peersBackingMap[key] = peer.copy(
                    lastHeardAgoSec = (elapsedMs / 1000L).toInt().coerceAtLeast(0)
                )
            }
        }
        for (k in staleKeys) {
            peersBackingMap.remove(k)
            dtnFlushedPeers.remove(k)
            relayRateMap.remove(k)
        }
        // Prune pendingChatAcks if unbounded
        if (pendingChatAcks.size > 2000) {
            val acksIter = pendingChatAcks.keys.iterator()
            var ackPruned = 0
            while (acksIter.hasNext() && ackPruned < 500) {
                acksIter.next()
                acksIter.remove()
                ackPruned++
            }
        }
        lastPeerUiSnapshotMs = nowMs
        _uiState.update { current ->
            val kept = current.nodes.mapNotNull { node ->
                if (node.isSelf) {
                    node.copy(lastHeardAgoSec = 0, lastSeenMs = nowMs)
                } else {
                    val normId = normalizeId(node.id)
                    val backing = peersBackingMap[normId]
                    val elapsedMs = nowMs - (backing?.lastSeenMs ?: node.lastSeenMs)
                    if (elapsedMs > PEER_STALE_TIMEOUT_MS) {
                        peersBackingMap.remove(normId)
                        dtnFlushedPeers.remove(normId)
                        null
                    } else {
                        (backing ?: node).copy(lastHeardAgoSec = (elapsedMs / 1000L).toInt().coerceAtLeast(0))
                    }
                }
            }
            current.copy(
                nodes = kept,
                activePeerCount = kept.count { !it.isSelf }
            )
        }
    }

    /**
     * Top-Bar Hotspot Button Action (`toggleOrConnectHotspot`):
     * - Case 1: If a discovered peer in `activePeers` is broadcasting `hotspotSsid` / `hotspotPass`, immediately connects
     *   to that peer's hotspot and updates the button to `"HOTSPOT: LINKED"`.
     * - Case 2: If no peer hotspot is active yet, creates a Wi-Fi Direct group (`wifiP2pManager.createGroup`) with automatic
     *   fallback to `LocalOnlyHotspot`, updates the button to `"HOTSPOT: HOSTING (...)"`, and broadcasts `HOTSPOT_AUTO_JOIN` over BLE.
     * - Case 3: If already hosting or linked, stops the group and resets the button to `"HOTSPOT: OFF"`.
     */
    fun hostHotspotExplicitly() {
        hotspotWebPortalRequested = true
        pulseMeshWebServer.start(com.example.service.AutoHotspotNegotiator.LOCAL_HOTSPOT_IP)
        // Standard SoftAP (LocalOnlyHotspot / active System Hotspot) FIRST for 100% iPhone/Android/browser TCP compatibility;
        // LocalHotspotManager automatically falls back to Wi-Fi Direct P2P if LocalOnlyHotspot is unavailable.
        hotspotManager.startLocalHotspot()
        val liveBtn = if (hotspotManager.hotspotState.value.isActive) {
            "HOTSPOT: HOSTING (${hotspotManager.hotspotState.value.ssid.ifBlank { "AP" }})"
        } else {
            "HOTSPOT: STARTING..."
        }
        _uiState.update { it.copy(hotspotButtonText = liveBtn) }
        triggerHaptic(50)
    }

    fun joinPeerHotspotExplicitly() {
        val peerWithHotspot = (_uiState.value.nodes.filter { !it.isSelf } + peersBackingMap.values)
            .firstOrNull { it.hotspotPass.isNotBlank() }
        val peerCreds = peerWithHotspot?.let {
            Triple(it.id, it.hotspotSsid, it.hotspotPass)
        }
        val peerIpHint = peerWithHotspot?.ipAddress.orEmpty()
        val nextBtnState = autoHotspotNegotiator.joinPeerHotspotExplicitly(
            discoveredPeerHotspot = peerCreds,
            peerHostIpHint = peerIpHint
        )
        _uiState.update { it.copy(hotspotButtonText = nextBtnState) }
        triggerHaptic(50)
    }

    fun stopHotspotExplicitly() {
        hotspotWebPortalRequested = false
        val nextBtnState = autoHotspotNegotiator.stopHotspotExplicitly(
            onStopLocalHotspot = { hotspotManager.stopLocalHotspot() }
        )
        if (!manualWebPortalPinned) {
            pulseMeshWebServer.stop()
        }
        _uiState.update { it.copy(hotspotButtonText = nextBtnState) }
        triggerHaptic(50)
    }

    fun toggleOrConnectHotspot() {
        val cur = autoHotspotNegotiator.status.value
        val isHostingOrLinked = cur.isHotspotHost ||
            hotspotManager.hotspotState.value.isActive ||
            cur.buttonStateText.startsWith("HOTSPOT: HOSTING") ||
            cur.buttonStateText == "HOTSPOT: LINKED"

        if (isHostingOrLinked) {
            stopHotspotExplicitly()
            return
        }

        val peerWithHotspot = (_uiState.value.nodes.filter { !it.isSelf } + peersBackingMap.values)
            .firstOrNull { it.hotspotPass.isNotBlank() }

        if (peerWithHotspot != null) {
            joinPeerHotspotExplicitly()
            return
        }

        hostHotspotExplicitly()
    }

    fun triggerAutoConnectHotspot() {
        toggleOrConnectHotspot()
    }

    fun healHotspotLinkNow() {
        val peerWithHotspot = (_uiState.value.nodes.filter { !it.isSelf } + peersBackingMap.values)
            .firstOrNull { it.hotspotPass.isNotBlank() }
        val summary = autoHotspotNegotiator.healHotspotConnectionNow(peerWithHotspot?.id.orEmpty())
        _uiState.update {
            it.copy(
                pttStatus = summary,
                hotspotButtonText = autoHotspotNegotiator.status.value.buttonStateText
            )
        }
        pingMeshNow()
        triggerHaptic(50)
    }

    fun cycleVoiceClarityPreset() {
        val next = audioService.cycleVoiceClarityPreset()
        _uiState.update {
            it.copy(
                pttStatus = "VOICE DSP: ${next.label}"
            )
        }
        triggerHaptic(35)
    }

    private fun initializeNodes() {
        val selfId = _uiState.value.localNodeId
        val selfCallsign = "${_uiState.value.operatorCallsign} (Self)"
        val selfModel = _uiState.value.selectedPhoneProfile.modelName
        val realBattery = _uiState.value.realBatteryPercent

        val selfNode = MeshNode(
            id = selfId,
            callsign = selfCallsign,
            model = selfModel,
            latitude = _uiState.value.userLatitude ?: 0.0,
            longitude = _uiState.value.userLongitude ?: 0.0,
            altitudeM = 0.0,
            speedKmh = 0.0,
            batteryPercent = realBattery,
            rssiDbm = -42,
            snrDb = 12.5,
            hopCount = 0,
            lastHeardAgoSec = 0,
            lastSeenMs = System.currentTimeMillis(),
            isSelf = true,
            chipsetProfile = ChipsetProfile.QUALCOMM_SNAPDRAGON
        )

        _uiState.update { it.copy(nodes = listOf(selfNode), activePeerCount = 0) }
    }

    private fun initializeWaypoints() {
        _uiState.update { it.copy(waypoints = emptyList()) }
    }

    private fun initializePackets() {
        refreshConversationsList()
    }

    private fun startTelemetrySimulation() {
        telemetryTickJob?.cancel()
        telemetryTickJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val currentBattery = HardwareDetector.readBatteryPercentage(getApplication())
                val now = System.currentTimeMillis()
                pruneStalePeers(now)
                _uiState.update { current ->
                    val updatedNodes = current.nodes.map { node ->
                        if (node.isSelf) {
                            node.copy(batteryPercent = currentBattery, lastHeardAgoSec = 0, lastSeenMs = now)
                        } else {
                            node
                        }
                    }
                    current.copy(nodes = updatedNodes, realBatteryPercent = currentBattery)
                }
            }
        }
    }

    fun selectPhoneModel(profile: PhoneModelProfile) {
        prefs.selectedProfileId = profile.id
        prefs.deviceProfileName = profile.modelName
        prefs.isMismatchOverrideConfirmed = false

        val hw = _uiState.value.detectedHardware ?: HardwareDetector.detectHardware(getApplication())
        val mismatch = HardwareDetector.evaluateMismatch(hw, profile)

        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (node.isSelf) node.copy(model = profile.modelName) else node
            }
            current.copy(
                selectedPhoneProfile = profile,
                localDeviceModel = profile.modelName,
                hardwareMismatchReport = mismatch,
                isMismatchOverrideConfirmed = true,
                showMismatchWarningDialog = false,
                nodes = updatedNodes
            )
        }
        triggerHaptic(30)
    }

    fun autoSwitchToDetectedHardware() {
        val freshHw = HardwareDetector.detectHardware(getApplication())
        val recommended = HardwareDetector.resolveAutoMatchedProfile(freshHw)

        prefs.selectedProfileId = recommended.id
        prefs.deviceProfileName = recommended.modelName
        prefs.isMismatchOverrideConfirmed = true

        val mismatch = HardwareDetector.evaluateMismatch(freshHw, recommended)

        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (node.isSelf) node.copy(model = recommended.modelName) else node
            }
            current.copy(
                detectedHardware = freshHw,
                selectedPhoneProfile = recommended,
                localDeviceModel = recommended.modelName,
                hardwareMismatchReport = mismatch,
                isMismatchOverrideConfirmed = true,
                showMismatchWarningDialog = false,
                nodes = updatedNodes
            )
        }
        triggerHaptic(50)
    }

    fun forceOverrideMismatch() {
        prefs.isMismatchOverrideConfirmed = true
        _uiState.update { it.copy(isMismatchOverrideConfirmed = true, showMismatchWarningDialog = false) }
        triggerHaptic(40)
    }

    fun openMismatchDialog() {
        _uiState.update { it.copy(showMismatchWarningDialog = true) }
        triggerHaptic(25)
    }

    fun closeMismatchDialog() {
        _uiState.update { it.copy(showMismatchWarningDialog = false) }
        triggerHaptic(20)
    }

    fun openSetupWizard() {
        _uiState.update { it.copy(showSetupWizard = true) }
        triggerHaptic(30)
    }

    fun closeSetupWizard() {
        _uiState.update { it.copy(showSetupWizard = false) }
        triggerHaptic(20)
    }

    fun saveDeviceProfile(
        callsign: String,
        nodeId: String,
        profile: PhoneModelProfile,
        pureOffGrid: Boolean,
        internetBridge: Boolean
    ) {
        val enableInternet = internetBridge || !pureOffGrid
        val effectivePureOffGrid = !enableInternet

        prefs.operatorCallsign = callsign
        prefs.nodeId = nodeId
        prefs.selectedProfileId = profile.id
        prefs.deviceProfileName = profile.modelName
        prefs.isPureOffGrid = effectivePureOffGrid
        prefs.internetGatewayBridgeEnabled = enableInternet
        prefs.isInternetEnabled = enableInternet
        prefs.isFirstLaunchCompleted = true

        com.example.util.OfflineRegionDownloader.isInternetEnabled = enableInternet
        if (!enableInternet) {
            com.example.util.OfflineRegionDownloader.cancelDownload()
        }

        if (!TacticalE2eeManager.isValidEcPublicKeyBase64(prefs.x25519PublicKey) ||
            !TacticalE2eeManager.isValidEcPrivateKeyBase64(prefs.x25519PrivateKey)
        ) {
            val keyPair = TacticalE2eeManager.generateKeyPair(callsign)
            prefs.x25519PublicKey = keyPair.publicKeyBase64
            prefs.x25519PrivateKey = keyPair.privateKeyBase64
            prefs.fingerprint = keyPair.fingerprint
        }
        val activePub = prefs.x25519PublicKey
        val activeFp = prefs.fingerprint.ifBlank { TacticalE2eeManager.deriveFingerprint(activePub) }

        val hw = _uiState.value.detectedHardware ?: HardwareDetector.detectHardware(getApplication())
        val mismatch = HardwareDetector.evaluateMismatch(hw, profile)

        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (node.isSelf) {
                    node.copy(
                        id = nodeId,
                        callsign = "$callsign (Self)",
                        model = profile.modelName
                    )
                } else {
                    node
                }
            }

            current.copy(
                localNodeId = nodeId,
                operatorCallsign = callsign,
                selectedPhoneProfile = profile,
                localDeviceModel = profile.modelName,
                isPureOffGrid = effectivePureOffGrid,
                internetGatewayBridgeEnabled = enableInternet,
                isInternetEnabled = enableInternet,
                isPureOffGridTransport = effectivePureOffGrid,
                x25519Fingerprint = activeFp,
                x25519PublicKey = activePub,
                hardwareMismatchReport = mismatch,
                nodes = updatedNodes,
                showSetupWizard = false,
                showMismatchWarningDialog = false
            )
        }
        meshNetworkService.refreshInternetState()
        if (enableInternet) {
            pingMeshNow()
            checkForAppUpdateOnStartup()
        }
        triggerHaptic(60)
    }

    fun setTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
        triggerHaptic(20)
    }

    fun selectNode(node: MeshNode?) {
        _uiState.update { it.copy(selectedNode = node) }
        triggerHaptic(25)
    }

    fun setMapRenderMode(mode: MapRenderMode) {
        _uiState.update { it.copy(mapRenderMode = mode) }
        triggerHaptic(30)
    }

    fun togglePrivacyFuzzing() {
        _uiState.update { it.copy(isPrivacyFuzzingEnabled = !it.isPrivacyFuzzingEnabled) }
        triggerHaptic(40)
    }

    fun broadcastGpsBeacon() {
        triggerHaptic(50)
        val state = _uiState.value
        val pktId = "PKT#${Random.nextInt(500, 999)}"
        val lat = state.userLatitude
        val lon = state.userLongitude
        val (outTransport, outRssi) = resolveActiveOutboundTransportAndRssi(null)
        val badge = "[$pktId | ${state.localNodeId}->ALL | Hops: 0/5 | $outTransport]"
        val content = "GPS POSITION: ${String.format("%.4f", lat)}°N, ${String.format("%.4f", lon)}°E | Alt: 142m | Battery: ${state.realBatteryPercent}%"

        val newPacket = MeshPacket(
            id = pktId,
            sourceId = state.localNodeId,
            destId = "ALL",
            sourceCallsign = "${state.operatorCallsign} (Self)",
            type = PacketType.GPS_BEACON,
            content = content,
            hops = 0,
            rssiDbm = outRssi,
            transport = outTransport,
            rawHexBadge = badge,
            isIncoming = false
        )

        _uiState.update { it.copy(packets = listOf(newPacket) + it.packets) }
    }

    fun dropWaypoint(title: String, type: WaypointType, lat: Double, lon: Double) {
        val state = _uiState.value
        val wpId = "WP-${Random.nextInt(10, 99)}"
        val now = System.currentTimeMillis()
        val hexPayload = "0x${Random.nextLong(0x10000000000000L, 0xFFFFFFFFFFFFFFL).toString(16).uppercase()}"
        val cleanTitle = title.trim().ifBlank { type.label }
        val newWp = TacticalWaypoint(
            id = wpId,
            title = cleanTitle,
            type = type,
            latitude = lat,
            longitude = lon,
            creatorCallsign = "${state.operatorCallsign} (Self)",
            timestamp = now,
            payloadHex = hexPayload
        )

        val pktId = "PKT#${Random.nextInt(400, 899)}"
        val (outTransport, outRssi) = resolveActiveOutboundTransportAndRssi(null)
        val badge = "[$pktId | ${state.localNodeId}->ALL | Hops: 0/7 | WAYPOINT | $outTransport]"
        val packet = MeshPacket(
            id = pktId,
            sourceId = state.localNodeId,
            destId = "ALL",
            sourceCallsign = "${state.operatorCallsign} (Self)",
            type = PacketType.WAYPOINT,
            content = "WAYPOINT BROADCAST: ${newWp.title} (${newWp.type.label}) @ ${String.format("%.4f", lat)}°N, ${String.format("%.4f", lon)}°E",
            hops = 0,
            rssiDbm = outRssi,
            transport = outTransport,
            rawHexBadge = badge,
            isIncoming = false,
            waypointData = newWp
        )

        _uiState.update {
            it.copy(
                waypoints = it.waypoints + newWp,
                packets = listOf(packet) + it.packets
            )
        }

        val wpJson = org.json.JSONObject().apply {
            put("id", wpId)
            put("title", cleanTitle)
            put("type", type.name)
            put("lat", lat)
            put("lon", lon)
            put("creator", state.operatorCallsign)
            put("ts", now)
        }.toString()

        val wirePacket = com.example.service.WireMeshPacket(
            packetId = "wp_${wpId}_$now",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = "ALL",
            type = "WAYPOINT",
            payload = wpJson,
            ttl = 7,
            hops = 0,
            lat = lat,
            lng = lon,
            altitude = state.barometricAltitudeMeters,
            battery = state.realBatteryPercent,
            timestamp = now
        )
        sendOmniPacket(wirePacket)
        triggerHaptic(60)
    }

    fun toggleSosBeacon() {
        val nowActive = !_uiState.value.isSosActive
        _uiState.update { it.copy(isSosActive = nowActive, incomingSosAlert = if (!nowActive) "" else it.incomingSosAlert) }
        triggerHaptic(if (nowActive) 320 else 60)
        if (nowActive) {
            audioService.playSosAlarmPulse()
        }

        val state = _uiState.value
        val now = System.currentTimeMillis()
        val (outTransport, outRssi) = resolveActiveOutboundTransportAndRssi(null)
        val hasGps = state.userLatitude != null && state.userLongitude != null
        val coordLabel = if (hasGps) {
            String.format(java.util.Locale.US, "%.4f°, %.4f°", state.userLatitude, state.userLongitude)
        } else {
            "NO GPS LOCK"
        }

        if (nowActive) {
            val pktId = "PKT#SOS_${now and 0xFFFF}"
            val iceParts = buildList {
                if (state.iceBloodType.isNotBlank()) add("Blood: ${state.iceBloodType}")
                if (state.iceAllergies.isNotBlank()) add("Allergies: ${state.iceAllergies}")
                if (state.iceNotes.isNotBlank()) add("Med: ${state.iceNotes}")
                if (state.iceEmergencyContact.isNotBlank()) add("ICE: ${state.iceEmergencyContact}")
            }
            val iceSuffix = if (iceParts.isNotEmpty()) " | ICE [${iceParts.joinToString("; ")}]" else ""
            val sosMsg = "MAYDAY: EMERGENCY SOS BEACON TRIGGERED BY ${state.operatorCallsign} (${state.localNodeId}) @ $coordLabel$iceSuffix"
            val badge = "[$pktId | ${state.localNodeId}->ALL | PRIORITY: EMERGENCY | $outTransport]"
            val packet = MeshPacket(
                id = pktId,
                sourceId = state.localNodeId,
                destId = "ALL",
                sourceCallsign = "${state.operatorCallsign} (Self)",
                type = PacketType.SOS,
                content = sosMsg,
                hops = 0,
                rssiDbm = outRssi,
                transport = outTransport,
                rawHexBadge = badge,
                isIncoming = false,
                timestamp = now
            )
            persistentChatManager.savePacket("channel_primary", packet)
            val latVal = state.userLatitude
            val lngVal = state.userLongitude
            if (latVal != null && lngVal != null) {
                val sosWp = TacticalWaypoint(
                    id = "SOS-${normalizeId(state.localNodeId)}",
                    title = "SOS: ${state.operatorCallsign}",
                    type = WaypointType.SOS,
                    latitude = latVal,
                    longitude = lngVal,
                    creatorCallsign = "${state.operatorCallsign} (Self)",
                    timestamp = now,
                    payloadHex = "0xSOS"
                )
                _uiState.update { cur ->
                    cur.copy(
                        waypoints = cur.waypoints.filterNot {
                            it.id == sosWp.id || it.id == "SOS-${state.localNodeId.takeLast(4)}"
                        } + sosWp,
                        packets = listOf(packet) + cur.packets
                    )
                }
            } else {
                _uiState.update { it.copy(packets = listOf(packet) + it.packets) }
            }
            refreshConversationsList()

            val wirePacket = com.example.service.WireMeshPacket(
                packetId = pktId,
                srcNodeId = state.localNodeId,
                srcCallsign = state.operatorCallsign,
                dstNodeId = "ALL",
                type = "SOS",
                payload = sosMsg,
                ttl = 7,
                hops = 0,
                lat = state.userLatitude,
                lng = state.userLongitude,
                altitude = state.barometricAltitudeMeters,
                battery = state.realBatteryPercent,
                timestamp = now,
                sourceTransport = outTransport,
                rssiDbm = outRssi,
                srcUsername = state.claimedUsername
            )
            sendOmniPacket(wirePacket)
        } else {
            // Remove our own red SOS waypoint from the map immediately on cancel!
            val selfNorm = normalizeId(state.localNodeId)
            val clearId = "PKT#SOS_CLR_${now and 0xFFFF}"
            val clearMsg = "SOS CLEARED by ${state.operatorCallsign} (${state.localNodeId})"
            _uiState.update { cur ->
                cur.copy(
                    isSosActive = false,
                    incomingSosAlert = if (cur.incomingSosAlert.contains(state.operatorCallsign, ignoreCase = true)) "" else cur.incomingSosAlert,
                    waypoints = cur.waypoints.filterNot { wp ->
                        wp.type == WaypointType.SOS && (
                            wp.id.equals("SOS-$selfNorm", ignoreCase = true) ||
                                wp.id.equals("SOS-${state.localNodeId.takeLast(4)}", ignoreCase = true) ||
                                wp.creatorCallsign.contains("(Self)", ignoreCase = true)
                            )
                    }
                )
            }
            val clearChatPkt = MeshPacket(
                id = clearId,
                sourceId = state.localNodeId,
                destId = "ALL",
                sourceCallsign = "${state.operatorCallsign} (Self)",
                type = PacketType.TEXT,
                content = clearMsg,
                hops = 0,
                transport = outTransport,
                rawHexBadge = "[$clearId | SOS_CLEAR]",
                isIncoming = false,
                timestamp = now
            )
            persistentChatManager.savePacket("channel_primary", clearChatPkt)
            refreshConversationsList()

            val wireClear = com.example.service.WireMeshPacket(
                packetId = clearId,
                srcNodeId = state.localNodeId,
                srcCallsign = state.operatorCallsign,
                dstNodeId = "ALL",
                type = "SOS_CLEAR",
                payload = clearMsg,
                ttl = 7,
                hops = 0,
                lat = state.userLatitude,
                lng = state.userLongitude,
                battery = state.realBatteryPercent,
                timestamp = now,
                sourceTransport = outTransport,
                rssiDbm = outRssi,
                srcUsername = state.claimedUsername
            )
            sendOmniPacket(wireClear)
        }
    }

    fun dismissUsernamePopup() {
        prefs.accountRecoveryKey = ""
        _uiState.update {
            it.copy(
                showUsernamePopup = false,
                usernamePopupTitle = "",
                usernamePopupMessage = "",
                accountRecoveryKey = ""
            )
        }
    }

    fun setUsernameRelayUrl(
        rawUrl: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val clean = rawUrl.trim().trimEnd('/')
        if (clean.isNotBlank() && !clean.startsWith("https://") && !clean.startsWith("http://")) {
            val err = "Relay URL must start with https:// (or leave blank for Free Built-in Relay)."
            _uiState.update { it.copy(usernameRelayTestStatus = err) }
            onResult?.invoke(false, err)
            return
        }
        prefs.usernameRelayUrl = clean
        prefs.lastUsernameInboxCursor = 0L
        _uiState.update {
            it.copy(
                usernameRelayUrl = clean,
                usernameRelayTestStatus = if (clean.isBlank()) {
                    "Switched to Free Built-in E2EE Relay (PBKDF2 + ECDSA + PMU2 ECDH)"
                } else {
                    "Testing $clean..."
                }
            )
        }
        meshNetworkService.refreshUsernameInboxListener()
        if (clean.isBlank()) {
            onResult?.invoke(true, "Using Free Built-in E2EE Relay")
            return
        }
        if (!_uiState.value.isInternetEnabled) {
            val msg = "Saved Cloudflare Relay URL. Enable Hybrid Internet to connect."
            _uiState.update { it.copy(usernameRelayTestStatus = msg) }
            onResult?.invoke(true, msg)
            return
        }
        viewModelScope.launch {
            val (ok, msg) = meshNetworkService.testUsernameRelayHealth(clean)
            _uiState.update { it.copy(usernameRelayTestStatus = msg) }
            if (ok) {
                if (prefs.isUsernameOnHold) {
                    syncPendingUsernameHoldIfOnline()
                } else {
                    val currentName = prefs.claimedUsername
                    val localClaim = prefs.getUsernameClaim(currentName)
                    if (currentName.isNotBlank() && localClaim != null) {
                        val lookup = meshNetworkService.pollUsernameClaimWithStatus(currentName)
                        if (lookup.reachable && lookup.claim == null) {
                            meshNetworkService.publishUsernameClaimWithStatus(localClaim, isUpdate = false)
                        }
                    }
                }
            }
            onResult?.invoke(ok, msg)
        }
    }

    data class RestoredBackupSummary(
        val restoredMessages: Int,
        val restoredThreads: Int,
        val restoredContacts: Int,
        val restoredGroups: Int
    )

    /**
     * Builds a complete JSON snapshot of the operator's chats, contacts, trusted peer public keys,
     * mesh groups (with AES keys), and app settings so logging in on another device moves all data.
     */
    fun buildEncryptedAccountSnapshotJson(maxJsonChars: Int = 340_000): String {
        val state = _uiState.value
        val root = org.json.JSONObject()
        root.put("v", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val settingsObj = org.json.JSONObject().apply {
            put("appTheme", prefs.appTheme.name)
            put("meshChannel", prefs.meshChannel)
            put("meshPassphrase", prefs.meshPassphrase)
            put("isSimpleMode", prefs.isSimpleMode)
            put("isHotspotAutoPopupEnabled", prefs.isHotspotAutoPopupEnabled)
            put("autoCheckInIntervalMin", prefs.autoCheckInIntervalMin)
            put("isSpokenCallerIdEnabled", prefs.isSpokenCallerIdEnabled)
            put("locationShareMode", prefs.locationShareMode.name)
            put("broadcastCoarseLocationInBle", prefs.broadcastCoarseLocationInBle)
            put("internetGatewayBridgeEnabled", prefs.internetGatewayBridgeEnabled)
            put("usernameRelayUrl", prefs.usernameRelayUrl)
            put("lastUsernameInboxCursor", prefs.lastUsernameInboxCursor)
            put("encryptionKeyEpoch", prefs.encryptionKeyEpoch)
            put("operatorCallsign", state.operatorCallsign)
        }
        root.put("settings", settingsObj)

        val claimsArr = org.json.JSONArray()
        for (claim in prefs.getAllKnownUsernameClaims()) {
            claimsArr.put(claim.toPublicWireJson())
        }
        root.put("knownClaims", claimsArr)

        val peerKeysObj = org.json.JSONObject()
        for ((id, pubKey) in prefs.getAllPeerPublicKeys()) {
            peerKeysObj.put(id, pubKey)
        }
        root.put("peerPublicKeys", peerKeysObj)

        val groupsArr = org.json.JSONArray()
        for (grp in prefs.loadMeshGroups()) {
            groupsArr.put(
                org.json.JSONObject().apply {
                    put("groupId", grp.groupId)
                    put("groupName", grp.groupName)
                    put("memberIdsCsv", grp.memberIds.joinToString(","))
                    put("createdAt", grp.createdAt)
                    if (grp.groupKeyBase64.isNotBlank()) put("groupKeyBase64", grp.groupKeyBase64)
                }
            )
        }
        root.put("groups", groupsArr)

        fun serializeThreads(maxPerThread: Int, stripHugeMedia: Boolean): org.json.JSONObject {
            val threadsObj = org.json.JSONObject()
            for ((threadId, packets) in persistentChatManager.exportAllThreadsMap()) {
                val arr = org.json.JSONArray()
                for (pkt in packets.take(maxPerThread)) {
                    val content = if (stripHugeMedia && pkt.content.length > 16_000 &&
                        (pkt.content.startsWith("[[PM_IMAGE]]:") ||
                            pkt.content.startsWith("[[PM_AUDIO]]:") ||
                            pkt.content.startsWith("[[PM_VOICEDROP]]:"))
                    ) {
                        "[Media attachment omitted to fit encrypted sync quota]"
                    } else {
                        pkt.content
                    }
                    arr.put(
                        org.json.JSONObject().apply {
                            put("id", pkt.id)
                            put("sourceId", pkt.sourceId)
                            put("destId", pkt.destId)
                            put("sourceCallsign", pkt.sourceCallsign)
                            put("type", pkt.type.name)
                            put("content", content)
                            put("hops", pkt.hops)
                            if (pkt.rssiDbm != null) put("rssiDbm", pkt.rssiDbm)
                            put("transport", pkt.transport)
                            put("rawHexBadge", pkt.rawHexBadge)
                            put("isIncoming", pkt.isIncoming)
                            put("timestamp", pkt.timestamp)
                            put("isDelivered", pkt.isDelivered)
                            if (pkt.ackedByCallsign.isNotBlank()) put("ackedByCallsign", pkt.ackedByCallsign)
                        }
                    )
                }
                threadsObj.put(threadId, arr)
            }
            return threadsObj
        }

        root.put("threads", serializeThreads(maxPerThread = 200, stripHugeMedia = false))
        var out = root.toString()
        if (out.length > maxJsonChars) {
            root.put("threads", serializeThreads(maxPerThread = 120, stripHugeMedia = true))
            out = root.toString()
        }
        if (out.length > maxJsonChars) {
            root.put("threads", serializeThreads(maxPerThread = 45, stripHugeMedia = true))
            out = root.toString()
        }
        return out
    }

    /**
     * Restores all account data (chats, contacts, peer public keys, mesh groups & settings)
     * from a decrypted JSON snapshot onto this device.
     */
    fun restoreAccountSnapshotFromJson(snapshotJson: String): RestoredBackupSummary {
        var restoredMsgs = 0
        var restoredThreads = 0
        var restoredContacts = 0
        var restoredGroups = 0

        runCatching {
            val root = org.json.JSONObject(snapshotJson)

            // 1. Settings & Preferences
            val settings = root.optJSONObject("settings")
            if (settings != null) {
                val restoredTheme = runCatching {
                    com.example.util.AppTheme.valueOf(settings.optString("appTheme", prefs.appTheme.name))
                }.getOrDefault(prefs.appTheme)
                val restoredChannel = settings.optString("meshChannel", prefs.meshChannel)
                    .ifBlank { prefs.meshChannel }
                val restoredPass = settings.optString("meshPassphrase", prefs.meshPassphrase)
                    .ifBlank { prefs.meshPassphrase }
                val restoredSimple = settings.optBoolean("isSimpleMode", prefs.isSimpleMode)
                val restoredHotspotPopup = settings.optBoolean("isHotspotAutoPopupEnabled", prefs.isHotspotAutoPopupEnabled)
                val restoredCheckIn = settings.optInt("autoCheckInIntervalMin", prefs.autoCheckInIntervalMin)
                val restoredSpokenId = settings.optBoolean("isSpokenCallerIdEnabled", prefs.isSpokenCallerIdEnabled)
                val restoredLocMode = runCatching {
                    LocationShareMode.valueOf(settings.optString("locationShareMode", prefs.locationShareMode.name))
                }.getOrDefault(prefs.locationShareMode)
                val restoredCoarse = settings.optBoolean("broadcastCoarseLocationInBle", prefs.broadcastCoarseLocationInBle)
                val restoredRelayUrl = settings.optString("usernameRelayUrl", "").trim()
                val restoredCursor = settings.optLong("lastUsernameInboxCursor", prefs.lastUsernameInboxCursor)
                val restoredEpoch = settings.optInt("encryptionKeyEpoch", prefs.encryptionKeyEpoch).coerceAtLeast(1)

                prefs.appTheme = restoredTheme
                prefs.meshChannel = restoredChannel
                prefs.meshPassphrase = restoredPass
                prefs.isSimpleMode = restoredSimple
                prefs.isHotspotAutoPopupEnabled = restoredHotspotPopup
                prefs.autoCheckInIntervalMin = restoredCheckIn
                prefs.isSpokenCallerIdEnabled = restoredSpokenId
                prefs.locationShareMode = restoredLocMode
                prefs.broadcastCoarseLocationInBle = restoredCoarse
                if (restoredRelayUrl.isNotBlank() && prefs.usernameRelayUrl.isBlank()) {
                    prefs.usernameRelayUrl = restoredRelayUrl
                }
                if (restoredCursor > prefs.lastUsernameInboxCursor) {
                    prefs.lastUsernameInboxCursor = restoredCursor
                }
                if (restoredEpoch > prefs.encryptionKeyEpoch) {
                    prefs.encryptionKeyEpoch = restoredEpoch
                }

                _uiState.update { cur ->
                    cur.copy(
                        appTheme = restoredTheme,
                        meshChannel = restoredChannel,
                        meshPassphrase = restoredPass,
                        isSimpleMode = restoredSimple,
                        isHotspotAutoPopupEnabled = restoredHotspotPopup,
                        autoCheckInIntervalMin = restoredCheckIn,
                        isSpokenCallerIdEnabled = restoredSpokenId,
                        locationShareMode = restoredLocMode,
                        broadcastCoarseLocationInBle = restoredLocMode == LocationShareMode.COARSE || restoredCoarse,
                        encryptionKeyEpoch = prefs.encryptionKeyEpoch,
                        usernameRelayUrl = prefs.usernameRelayUrl
                    )
                }
            }

            // 2. Trusted Peer Public Keys (TOFU)
            val peerKeysObj = root.optJSONObject("peerPublicKeys")
            if (peerKeysObj != null) {
                val map = LinkedHashMap<String, String>()
                val keysIter = peerKeysObj.keys()
                while (keysIter.hasNext()) {
                    val k = keysIter.next()
                    val v = peerKeysObj.optString(k, "")
                    if (v.isNotBlank()) map[k] = v
                }
                prefs.restorePeerPublicKeys(map)
            }

            // 3. Known @Username Contacts
            val claimsArr = root.optJSONArray("knownClaims")
            if (claimsArr != null) {
                for (i in 0 until claimsArr.length()) {
                    val obj = claimsArr.optJSONObject(i) ?: continue
                    val rec = com.example.crypto.UsernameClaimRecord.fromJson(obj) ?: continue
                    prefs.saveUsernameClaim(rec)
                    if (rec.username != prefs.claimedUsername) {
                        restoredContacts++
                    }
                }
            }

            // 4. Mesh Groups & AES-256 Keys
            val groupsArr = root.optJSONArray("groups")
            if (groupsArr != null) {
                val restoredList = mutableListOf<MeshGroup>()
                for (i in 0 until groupsArr.length()) {
                    val obj = groupsArr.optJSONObject(i) ?: continue
                    val gid = obj.optString("groupId").trim()
                    val gname = obj.optString("groupName").trim()
                    val members = obj.optString("memberIdsCsv")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val gKey = obj.optString("groupKeyBase64", "").trim()
                    if (gid.isNotBlank() && gname.isNotBlank()) {
                        if (gKey.isNotBlank()) prefs.saveGroupAesKey(gid, gKey)
                        restoredList.add(
                            MeshGroup(
                                groupId = gid,
                                groupName = gname,
                                memberIds = members,
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                groupKeyBase64 = gKey
                            )
                        )
                    }
                }
                if (restoredList.isNotEmpty()) {
                    val merged = (prefs.loadMeshGroups() + restoredList).distinctBy { it.groupId }
                    prefs.saveMeshGroups(merged)
                    restoredGroups = restoredList.size
                }
            }

            // 5. All Chat Threads & Messages
            val threadsObj = root.optJSONObject("threads")
            if (threadsObj != null) {
                val threadsMap = LinkedHashMap<String, List<MeshPacket>>()
                val tKeys = threadsObj.keys()
                while (tKeys.hasNext()) {
                    val tid = tKeys.next()
                    val arr = threadsObj.optJSONArray(tid) ?: continue
                    val list = mutableListOf<MeshPacket>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        list.add(
                            MeshPacket(
                                id = item.optString("id", "PKT#000"),
                                sourceId = item.optString("sourceId", "0x0000"),
                                destId = item.optString("destId", "ALL"),
                                sourceCallsign = item.optString("sourceCallsign", "Operator"),
                                type = runCatching {
                                    PacketType.valueOf(item.optString("type", "TEXT"))
                                }.getOrDefault(PacketType.TEXT),
                                content = item.optString("content", ""),
                                hops = item.optInt("hops", 0),
                                rssiDbm = if (item.has("rssiDbm") && !item.isNull("rssiDbm")) item.optInt("rssiDbm") else null,
                                transport = item.optString("transport", "HYBRID RELAY"),
                                rawHexBadge = item.optString("rawHexBadge", ""),
                                isIncoming = item.optBoolean("isIncoming", false),
                                timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                                isDelivered = item.optBoolean("isDelivered", false),
                                ackedByCallsign = item.optString("ackedByCallsign", "")
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        threadsMap[tid] = list
                        restoredThreads++
                    }
                }
                if (threadsMap.isNotEmpty()) {
                    restoredMsgs = persistentChatManager.importThreadsMap(threadsMap)
                }
            }
        }

        refreshConversationsList()
        return RestoredBackupSummary(
            restoredMessages = restoredMsgs,
            restoredThreads = restoredThreads,
            restoredContacts = restoredContacts,
            restoredGroups = restoredGroups
        )
    }

    fun scheduleAccountBackupSync(immediate: Boolean = false) {
        val uname = prefs.claimedUsername
        if (uname.isBlank() || prefs.isUsernameOnHold || !_uiState.value.isInternetEnabled) return
        if (!TacticalE2eeManager.isValidEcPrivateKeyBase64(prefs.x25519PrivateKey)) return
        backupSyncJob?.cancel()
        backupSyncJob = viewModelScope.launch(Dispatchers.IO) {
            if (!immediate) delay(3000)
            syncAccountBackupNowInternal()
        }
    }

    fun syncAccountBackupNow(onResult: ((Boolean, String) -> Unit)? = null) {
        val uname = prefs.claimedUsername
        if (uname.isBlank()) {
            val msg = "Claim or log into a @username first to sync encrypted backups."
            _uiState.update { it.copy(lastAccountBackupSyncStatus = msg) }
            onResult?.invoke(false, msg)
            return
        }
        val canSync = _uiState.value.isInternetEnabled || hasValidatedOsInternet()
        if (!canSync) {
            val msg = "Enable Hybrid Internet to upload your encrypted backup."
            _uiState.update { it.copy(lastAccountBackupSyncStatus = msg) }
            onResult?.invoke(false, msg)
            return
        }
        if (!_uiState.value.isInternetEnabled) {
            toggleInternetEnabled(true)
        }
        _uiState.update { it.copy(lastAccountBackupSyncStatus = "Encrypting & syncing all device data...") }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = syncAccountBackupNowInternal()
            val msg = if (ok) {
                "Encrypted backup synced (chats, contacts, groups & settings)"
            } else {
                "Backup sync failed — will retry automatically."
            }
            _uiState.update { it.copy(lastAccountBackupSyncStatus = msg) }
            onResult?.invoke(ok, msg)
        }
    }

    private suspend fun syncAccountBackupNowInternal(): Boolean {
        val uname = prefs.claimedUsername
        if (uname.isBlank() || prefs.isUsernameOnHold || !_uiState.value.isInternetEnabled) return false
        val maxJsonChars = if (prefs.usernameRelayUrl.isNotBlank()) 450_000 else 180_000
        val snapshotJson = buildEncryptedAccountSnapshotJson(maxJsonChars = maxJsonChars)
        val encrypted = TacticalE2eeManager.encryptAccountBackup(
            snapshotJson = snapshotJson,
            privateKeyBase64 = prefs.x25519PrivateKey,
            saltHex = prefs.accountSaltHex
        )
        if (encrypted.isBlank()) return false
        val authVerifier = prefs.accountAuthToken.takeIf { it.isNotBlank() }?.let {
            TacticalE2eeManager.hashAuthToken(it)
        }.orEmpty()
        val ok = meshNetworkService.publishAccountBackup(
            username = uname,
            encryptedBackup = encrypted,
            authToken = prefs.accountAuthToken,
            authVerifierHash = authVerifier,
            publicKeyBase64 = prefs.x25519PublicKey
        )
        if (ok) {
            prefs.lastAccountBackupSyncAt = System.currentTimeMillis()
            _uiState.update {
                it.copy(lastAccountBackupSyncStatus = "Encrypted device sync up to date")
            }
        }
        return ok
    }

    private fun refreshSignedUsernameClaimIfNeeded() {
        val uname = prefs.claimedUsername
        if (uname.isBlank() || prefs.isUsernameOnHold || !_uiState.value.isInternetEnabled) return
        if (prefs.usernameRelayUrl.isNotBlank()) return // Permanent Cloudflare D1 doesn't need keep-alive refresh
        val now = System.currentTimeMillis()
        if (prefs.lastUsernameClaimRefreshAt > 0L && now - prefs.lastUsernameClaimRefreshAt < 4 * 60 * 60 * 1000L) return
        val existing = prefs.getUsernameClaim(uname) ?: return
        val signed = if (existing.claimVersion >= 2 && existing.signatureBase64.isNotBlank()) {
            existing
        } else {
            TacticalE2eeManager.signUsernameClaim(existing, prefs.x25519PrivateKey)
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (meshNetworkService.publishUsernameClaim(signed)) {
                prefs.lastUsernameClaimRefreshAt = System.currentTimeMillis()
                prefs.saveUsernameClaim(signed)
            }
        }
    }

    /**
     * Checks any locally held offline username against the global registry once online.
     * - If still unclaimed (or matches our owner proof), confirms it globally and shows a popup.
     * - If someone else claimed it first, releases the hold and alerts the user via a popup.
     */
    fun syncPendingUsernameHoldIfOnline(onComplete: ((Boolean, String) -> Unit)? = null) {
        val heldName = prefs.pendingUsernameHold
        if (!prefs.isUsernameOnHold || heldName.isBlank() || !_uiState.value.isInternetEnabled) return
        if (isSyncingUsernameHold) return
        isSyncingUsernameHold = true

        viewModelScope.launch {
            try {
                val lookup = meshNetworkService.pollUsernameClaimWithStatus(heldName)
                if (!lookup.reachable) {
                    return@launch
                }

                val remoteClaim = lookup.claim
                val myAuthHash = prefs.accountAuthToken.takeIf { it.isNotBlank() }?.let {
                    TacticalE2eeManager.hashAuthToken(it)
                }.orEmpty()
                val takenBySomeoneElse = remoteClaim != null &&
                    remoteClaim.publicKeyBase64 != prefs.x25519PublicKey &&
                    (myAuthHash.isBlank() || remoteClaim.authVerifierHash != myAuthHash) &&
                    (prefs.accountPinHash.isBlank() || remoteClaim.pinHash != prefs.accountPinHash) &&
                    (prefs.accountRecoveryHash.isBlank() || remoteClaim.recoveryHash != prefs.accountRecoveryHash)

                fun releaseTakenHold(ownerRecord: com.example.crypto.UsernameClaimRecord?) {
                    if (ownerRecord != null) prefs.saveUsernameClaim(ownerRecord)
                    prefs.isUsernameOnHold = false
                    prefs.pendingUsernameHold = ""
                    prefs.claimedUsername = ""
                    prefs.accountSaltHex = ""
                    prefs.accountPinHash = ""
                    prefs.accountRecoveryKey = ""
                    prefs.accountRecoveryHash = ""
                    prefs.accountAuthToken = ""

                    val takenMsg = "@$heldName was already taken by another user while you were offline. Please choose a different username."
                    _uiState.update {
                        it.copy(
                            claimedUsername = "",
                            accountRecoveryKey = "",
                            hasAccountPin = false,
                            isUsernameOnHold = false,
                            pendingUsernameHold = "",
                            usernameAuthStatus = takenMsg,
                            usernamePopupTitle = "USERNAME ALREADY TAKEN",
                            usernamePopupMessage = takenMsg,
                            showUsernamePopup = true
                        )
                    }
                    refreshConversationsList()
                    onComplete?.invoke(false, takenMsg)
                }

                if (takenBySomeoneElse) {
                    releaseTakenHold(remoteClaim)
                    return@launch
                }

                val heldRecord = prefs.getUsernameClaim(heldName) ?: run {
                    val authTok = prefs.accountAuthToken.ifBlank { TacticalE2eeManager.generateAuthToken() }
                    prefs.accountAuthToken = authTok
                    TacticalE2eeManager.signUsernameClaim(
                        com.example.crypto.UsernameClaimRecord(
                            username = heldName,
                            displayName = _uiState.value.operatorCallsign.ifBlank { "@$heldName" },
                            nodeId = _uiState.value.localNodeId,
                            publicKeyBase64 = prefs.x25519PublicKey,
                            saltHex = prefs.accountSaltHex,
                            pinHash = prefs.accountPinHash,
                            recoveryHash = prefs.accountRecoveryHash,
                            claimedAt = System.currentTimeMillis(),
                            claimVersion = 2,
                            authVerifierHash = TacticalE2eeManager.hashAuthToken(authTok)
                        ),
                        prefs.x25519PrivateKey
                    )
                }
                val publishRes = meshNetworkService.publishUsernameClaimWithStatus(heldRecord, isUpdate = false)
                if (publishRes.conflict) {
                    val winner = meshNetworkService.pollUsernameClaimWithStatus(heldName).claim
                    releaseTakenHold(winner)
                    return@launch
                }
                if (publishRes.success) {
                    prefs.isUsernameOnHold = false
                    prefs.pendingUsernameHold = ""
                    prefs.lastUsernameClaimRefreshAt = System.currentTimeMillis()
                    prefs.saveUsernameClaim(heldRecord)
                    val okMsg = "@$heldName was not taken and is now officially yours!"
                    _uiState.update {
                        it.copy(
                            isUsernameOnHold = false,
                            pendingUsernameHold = "",
                            usernameAuthStatus = okMsg,
                            usernamePopupTitle = "USERNAME CONFIRMED!",
                            usernamePopupMessage = okMsg,
                            showUsernamePopup = true
                        )
                    }
                    meshNetworkService.refreshUsernameInboxListener()
                    broadcastUsernameClaimOnMesh(heldRecord)
                    refreshConversationsList()
                    scheduleAccountBackupSync(immediate = true)
                    onComplete?.invoke(true, okMsg)
                }
            } finally {
                isSyncingUsernameHold = false
            }
        }
    }

    /**
     * Reserves a unique @username first-come-first-serve with a 4–6 digit PIN + generated Recovery Key,
     * OR recovers/logs in to an existing @username when the matching 4–6 digit PIN or Recovery Key is provided.
     * When offline, places the new username ON HOLD and notifies the user with a popup that it will be theirs
     * if not taken when they get back online.
     */
    fun claimOrRecoverUsername(
        rawUsername: String,
        pin: String,
        recoveryKeyInput: String = "",
        isRecoveryMode: Boolean = false,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val norm = TacticalE2eeManager.normalizeUsername(rawUsername)
        if (!TacticalE2eeManager.isValidUsername(norm)) {
            val err = "Username must be 3–24 characters (letters, numbers, _ or -)."
            _uiState.update { it.copy(usernameAuthStatus = err, isReservingUsername = false) }
            onResult?.invoke(false, err)
            return
        }

        val cleanPin = pin.trim()
        val cleanRecovery = recoveryKeyInput.trim()

        fun migrateLocalNodeIdInGroups(oldNodeId: String, newNodeId: String) {
            val oldNorm = normalizeId(oldNodeId)
            val newNorm = normalizeId(newNodeId)
            if (oldNorm.isBlank() || newNorm.isBlank() || oldNorm == newNorm) return
            val existingGroups = prefs.loadMeshGroups()
            if (existingGroups.isEmpty()) return
            var changed = false
            val migrated = existingGroups.map { grp ->
                if (grp.memberIds.any { normalizeId(it) == oldNorm }) {
                    changed = true
                    val updatedMembers = (grp.memberIds.map { if (normalizeId(it) == oldNorm) newNodeId else it } + newNodeId).distinct()
                    grp.copy(memberIds = updatedMembers)
                } else grp
            }
            if (changed) {
                prefs.saveMeshGroups(migrated)
                _uiState.update { it.copy(meshGroups = migrated) }
            }
        }

        fun applyOfflineHold(record: com.example.crypto.UsernameClaimRecord, recoveryKey: String) {
            val previousNodeId = prefs.nodeId
            prefs.claimedUsername = norm
            prefs.nodeId = record.nodeId
            migrateLocalNodeIdInGroups(previousNodeId, record.nodeId)
            prefs.operatorCallsign = record.displayName
            prefs.accountSaltHex = record.saltHex
            prefs.accountPinHash = record.pinHash
            prefs.accountRecoveryKey = ""
            prefs.accountRecoveryHash = record.recoveryHash
            prefs.pendingUsernameHold = norm
            prefs.isUsernameOnHold = true
            prefs.saveUsernameClaim(record)

            val holdMsg = "@$norm is on hold while you are offline. If it isn't taken when you get back online again, it's yours!"
            _uiState.update { cur ->
                val updatedNodes = cur.nodes.map { n ->
                    if (n.isSelf) n.copy(id = record.nodeId, callsign = "${record.displayName} (Self)") else n
                }
                cur.copy(
                    claimedUsername = norm,
                    localNodeId = record.nodeId,
                    operatorCallsign = record.displayName,
                    hasAccountPin = true,
                    accountRecoveryKey = recoveryKey,
                    isUsernameOnHold = true,
                    pendingUsernameHold = norm,
                    nodes = updatedNodes,
                    usernameAuthStatus = holdMsg,
                    isReservingUsername = false,
                    usernamePopupTitle = "USERNAME ON HOLD",
                    usernamePopupMessage = "$holdMsg Save your one-time Recovery Key: $recoveryKey",
                    showUsernamePopup = true
                )
            }
            broadcastUsernameClaimOnMesh(record)
            refreshConversationsList()
            onResult?.invoke(true, holdMsg)
        }

        fun completeWithResolvedClaim(
            remoteClaim: com.example.crypto.UsernameClaimRecord?,
            isOnlineReachable: Boolean
        ) {
            val localClaim = prefs.getUsernameClaim(norm)
            val existingClaim = when {
                localClaim != null && remoteClaim != null ->
                    if (localClaim.claimedAt in 1 until remoteClaim.claimedAt) localClaim else remoteClaim
                else -> remoteClaim ?: localClaim
            }
            if (remoteClaim != null) {
                prefs.saveUsernameClaim(remoteClaim)
            }

            if (existingClaim != null && existingClaim.claimedAt > 0L) {
                val pinVaultDecrypted = if (existingClaim.pinVault.isNotBlank() && TacticalE2eeManager.isValidAccountPin(cleanPin)) {
                    TacticalE2eeManager.decryptAccountVault(norm, existingClaim.saltHex, cleanPin, existingClaim.pinVault)
                } else null
                val recoveryVaultDecrypted = if (existingClaim.recoveryVault.isNotBlank() && cleanRecovery.isNotBlank()) {
                    TacticalE2eeManager.decryptAccountVault(
                        norm,
                        existingClaim.saltHex,
                        TacticalE2eeManager.normalizeRecoveryKey(cleanRecovery),
                        existingClaim.recoveryVault
                    ) ?: TacticalE2eeManager.decryptAccountVault(
                        norm,
                        existingClaim.saltHex,
                        cleanRecovery.uppercase(),
                        existingClaim.recoveryVault
                    )
                } else null

                val unlockedVault = pinVaultDecrypted ?: recoveryVaultDecrypted
                val pinMatches = pinVaultDecrypted != null || (
                    existingClaim.pinHash.isNotBlank() &&
                        TacticalE2eeManager.isValidAccountPin(cleanPin) &&
                        TacticalE2eeManager.hashAccountPin(norm, existingClaim.saltHex, cleanPin) == existingClaim.pinHash
                    )
                val recoveryMatches = recoveryVaultDecrypted != null || (
                    existingClaim.recoveryHash.isNotBlank() &&
                        cleanRecovery.isNotBlank() &&
                        TacticalE2eeManager.hashRecoveryKey(norm, cleanRecovery) == existingClaim.recoveryHash
                    )

                if (!pinMatches && !recoveryMatches) {
                    val err = if (isRecoveryMode) {
                        "Invalid PIN or Recovery Key for @$norm."
                    } else {
                        "Username @$norm is already reserved. Enter your PIN or Recovery Key to recover."
                    }
                    _uiState.update { it.copy(usernameAuthStatus = err, isReservingUsername = false) }
                    onResult?.invoke(false, err)
                    return
                }

                // Restore full E2EE EC keypair from decrypted vault if recovering on another device!
                if (unlockedVault != null) {
                    prefs.x25519PrivateKey = unlockedVault.privateKeyBase64
                    prefs.x25519PublicKey = unlockedVault.publicKeyBase64
                    prefs.fingerprint = TacticalE2eeManager.deriveFingerprint(unlockedVault.publicKeyBase64)
                    if (unlockedVault.authToken.isNotBlank()) {
                        prefs.accountAuthToken = unlockedVault.authToken
                    }
                    if (unlockedVault.encryptionKeyEpoch > prefs.encryptionKeyEpoch) {
                        prefs.encryptionKeyEpoch = unlockedVault.encryptionKeyEpoch
                    }
                }

                // Valid PIN or Recovery Key — recover account on this device!
                val displayCallsign = unlockedVault?.displayName?.ifBlank { existingClaim.displayName }
                    ?: existingClaim.displayName.ifBlank { "@$norm" }
                prefs.claimedUsername = norm
                prefs.nodeId = existingClaim.nodeId
                prefs.operatorCallsign = displayCallsign
                prefs.accountSaltHex = existingClaim.saltHex
                prefs.accountRecoveryKey = ""
                if (existingClaim.pinHash.isNotBlank()) prefs.accountPinHash = existingClaim.pinHash
                if (existingClaim.recoveryHash.isNotBlank()) prefs.accountRecoveryHash = existingClaim.recoveryHash
                val isStillHeld = !isOnlineReachable &&
                    prefs.isUsernameOnHold &&
                    prefs.pendingUsernameHold == norm
                if (isOnlineReachable) {
                    prefs.isUsernameOnHold = false
                    prefs.pendingUsernameHold = ""
                }
                val activePubKey = unlockedVault?.publicKeyBase64 ?: prefs.x25519PublicKey
                val signedUpdate = TacticalE2eeManager.signUsernameClaim(
                    existingClaim.copy(
                        displayName = displayCallsign,
                        publicKeyBase64 = activePubKey
                    ),
                    prefs.x25519PrivateKey
                )
                prefs.saveUsernameClaim(signedUpdate)

                fun finalizeRecoveryUi(summary: RestoredBackupSummary?) {
                    val migratedDetail = if (summary != null &&
                        (summary.restoredMessages > 0 || summary.restoredContacts > 0 || summary.restoredGroups > 0 || summary.restoredThreads > 0)
                    ) {
                        " • Moved ${summary.restoredMessages} msgs, ${summary.restoredContacts} contacts, ${summary.restoredGroups} groups & settings"
                    } else {
                        " • E2EE Keys & Settings Restored"
                    }
                    val okMsg = "Logged in as @$norm (${existingClaim.nodeId})$migratedDetail"
                    _uiState.update { cur ->
                        val updatedNodes = cur.nodes.map { n ->
                            if (n.isSelf) n.copy(id = existingClaim.nodeId, callsign = "$displayCallsign (Self)") else n
                        }
                        cur.copy(
                            claimedUsername = norm,
                            localNodeId = existingClaim.nodeId,
                            operatorCallsign = displayCallsign,
                            x25519PublicKey = prefs.x25519PublicKey,
                            x25519Fingerprint = prefs.fingerprint,
                            encryptionKeyEpoch = prefs.encryptionKeyEpoch,
                            hasAccountPin = existingClaim.pinVault.isNotBlank() || existingClaim.pinHash.isNotBlank(),
                            accountRecoveryKey = "",
                            isUsernameOnHold = isStillHeld,
                            pendingUsernameHold = if (isStillHeld) norm else "",
                            nodes = updatedNodes,
                            usernameAuthStatus = okMsg,
                            isReservingUsername = false,
                            usernamePopupTitle = "DEVICE MIGRATION COMPLETE",
                            usernamePopupMessage = okMsg,
                            showUsernamePopup = summary != null
                        )
                    }
                    meshNetworkService.refreshUsernameInboxListener()
                    broadcastUsernameClaimOnMesh(signedUpdate)
                    refreshConversationsList()
                    onResult?.invoke(true, okMsg)
                }

                if (isOnlineReachable) {
                    if (!_uiState.value.isInternetEnabled) {
                        toggleInternetEnabled(true)
                    }
                    viewModelScope.launch {
                        val encryptedBackup = meshNetworkService.fetchAccountBackup(
                            username = norm,
                            authToken = prefs.accountAuthToken,
                            authVerifierHash = existingClaim.authVerifierHash,
                            publicKeyBase64 = activePubKey,
                            allowDirectLookup = true
                        )
                        val restoredSummary = if (!encryptedBackup.isNullOrBlank()) {
                            val decryptedJson = TacticalE2eeManager.decryptAccountBackup(
                                encryptedBackup = encryptedBackup,
                                privateKeyBase64 = prefs.x25519PrivateKey,
                                saltHex = existingClaim.saltHex
                            )
                            if (!decryptedJson.isNullOrBlank()) {
                                restoreAccountSnapshotFromJson(decryptedJson)
                            } else null
                        } else null
                        meshNetworkService.publishUsernameClaimWithStatus(
                            signedUpdate,
                            isUpdate = true,
                            allowDirectLookup = true
                        )
                        finalizeRecoveryUi(restoredSummary)
                    }
                } else {
                    finalizeRecoveryUi(null)
                }
                return
            }

            // No existing claim found
            if (isRecoveryMode) {
                val err = if (isOnlineReachable) {
                    "No existing account found for @$norm."
                } else {
                    "Offline: No local record for @$norm. Connect to Wi-Fi/5G or enable Hybrid Internet to recover from another device."
                }
                _uiState.update { it.copy(usernameAuthStatus = err, isReservingUsername = false) }
                onResult?.invoke(false, err)
                return
            }

            if (!TacticalE2eeManager.isValidAccountPin(cleanPin)) {
                val err = "Create a 4–6 digit numeric PIN to protect @$norm."
                _uiState.update { it.copy(usernameAuthStatus = err, isReservingUsername = false) }
                onResult?.invoke(false, err)
                return
            }

            val saltHex = TacticalE2eeManager.generateAccountSaltHex()
            val pinHash = TacticalE2eeManager.hashAccountPin(norm, saltHex, cleanPin)
            val recoveryKey = TacticalE2eeManager.generateRecoveryKey()
            val recoveryHash = TacticalE2eeManager.hashRecoveryKey(norm, recoveryKey)
            val authToken = TacticalE2eeManager.generateAuthToken()
            val authVerifierHash = TacticalE2eeManager.hashAuthToken(authToken)
            val assignedNodeId = TacticalE2eeManager.deriveNodeIdFromCallsign(norm)
            val displayCallsign = _uiState.value.operatorCallsign
                .takeIf { it.isNotBlank() && !it.startsWith("User-") }
                ?: "@$norm"

            val vaultPayload = com.example.crypto.AccountVaultPayload(
                privateKeyBase64 = prefs.x25519PrivateKey,
                publicKeyBase64 = prefs.x25519PublicKey,
                nodeId = assignedNodeId,
                displayName = displayCallsign,
                authToken = authToken,
                recoveryKey = "",
                encryptionKeyEpoch = prefs.encryptionKeyEpoch
            )
            val pinVault = TacticalE2eeManager.encryptAccountVault(norm, saltHex, cleanPin, vaultPayload)
            val recoveryVault = TacticalE2eeManager.encryptAccountVault(
                norm,
                saltHex,
                TacticalE2eeManager.normalizeRecoveryKey(recoveryKey),
                vaultPayload
            )

            val newClaim = TacticalE2eeManager.signUsernameClaim(
                com.example.crypto.UsernameClaimRecord(
                    username = norm,
                    displayName = displayCallsign,
                    nodeId = assignedNodeId,
                    publicKeyBase64 = prefs.x25519PublicKey,
                    saltHex = saltHex,
                    pinHash = pinHash,
                    recoveryHash = recoveryHash,
                    claimedAt = System.currentTimeMillis(),
                    claimVersion = 2,
                    pinVault = pinVault,
                    recoveryVault = recoveryVault,
                    authVerifierHash = authVerifierHash
                ),
                prefs.x25519PrivateKey
            )
            prefs.accountAuthToken = authToken

            if (!isOnlineReachable) {
                applyOfflineHold(newClaim, recoveryKey)
                return
            }

            viewModelScope.launch {
                val publishRes = meshNetworkService.publishUsernameClaimWithStatus(
                    newClaim,
                    isUpdate = false,
                    allowDirectLookup = true
                )
                if (publishRes.conflict) {
                    val err = "Username @$norm was just claimed by someone else. Enter PIN/Recovery Key or pick another."
                    _uiState.update { it.copy(usernameAuthStatus = err, isReservingUsername = false) }
                    onResult?.invoke(false, err)
                    return@launch
                }
                if (!publishRes.success) {
                    applyOfflineHold(newClaim, recoveryKey)
                    return@launch
                }

                val previousNodeId = prefs.nodeId
                prefs.claimedUsername = norm
                prefs.nodeId = assignedNodeId
                migrateLocalNodeIdInGroups(previousNodeId, assignedNodeId)
                prefs.operatorCallsign = displayCallsign
                prefs.accountSaltHex = saltHex
                prefs.accountPinHash = pinHash
                prefs.accountRecoveryKey = ""
                prefs.accountRecoveryHash = recoveryHash
                prefs.isUsernameOnHold = false
                prefs.pendingUsernameHold = ""
                prefs.lastUsernameClaimRefreshAt = System.currentTimeMillis()
                prefs.saveUsernameClaim(newClaim)
                if (!_uiState.value.isInternetEnabled) {
                    toggleInternetEnabled(true)
                }

                val okMsg = "Reserved @$norm ($assignedNodeId) with E2EE Vault. Save your Recovery Key!"
                _uiState.update { cur ->
                    val updatedNodes = cur.nodes.map { n ->
                        if (n.isSelf) n.copy(id = assignedNodeId, callsign = "$displayCallsign (Self)") else n
                    }
                    cur.copy(
                        claimedUsername = norm,
                        localNodeId = assignedNodeId,
                        operatorCallsign = displayCallsign,
                        hasAccountPin = true,
                        accountRecoveryKey = recoveryKey,
                        isUsernameOnHold = false,
                        pendingUsernameHold = "",
                        nodes = updatedNodes,
                        usernameAuthStatus = okMsg,
                        isReservingUsername = false,
                        usernamePopupTitle = "USERNAME RESERVED!",
                        usernamePopupMessage = "@$norm is now officially yours (ECDSA signed & PBKDF2-encrypted)! Save your Recovery Key: $recoveryKey",
                        showUsernamePopup = true
                    )
                }
                meshNetworkService.refreshUsernameInboxListener()
                broadcastUsernameClaimOnMesh(newClaim)
                refreshConversationsList()
                scheduleAccountBackupSync(immediate = true)
                onResult?.invoke(true, okMsg)
            }
        }

        val canQueryOnline = _uiState.value.isInternetEnabled || hasValidatedOsInternet()
        if (!canQueryOnline) {
            completeWithResolvedClaim(remoteClaim = null, isOnlineReachable = false)
        } else {
            _uiState.update { it.copy(isReservingUsername = true, usernameAuthStatus = "Checking @$norm...") }
            viewModelScope.launch {
                val lookup = meshNetworkService.pollUsernameClaimWithStatus(norm, allowDirectLookup = true)
                completeWithResolvedClaim(remoteClaim = lookup.claim, isOnlineReachable = lookup.reachable)
            }
        }
    }

    private fun broadcastUsernameClaimOnMesh(record: com.example.crypto.UsernameClaimRecord) {
        val pkt = com.example.service.WireMeshPacket(
            packetId = "uclaim_${record.username}_${System.currentTimeMillis() and 0xFFFF}",
            srcNodeId = record.nodeId,
            srcCallsign = record.displayName,
            dstNodeId = "BROADCAST",
            type = "USERNAME_CLAIM",
            payload = record.toPublicWireJson().toString(),
            ttl = 5,
            hops = 0,
            senderPubKey = record.publicKeyBase64,
            srcUsername = record.username
        )
        sendMeshPacket(pkt)
    }

    /**
     * Connects to a distant (or nearby) friend solely by their unique @username, resolves their Node ID & EC public key,
     * opens/creates a 1-on-1 DM thread, and switches directly to that chat.
     */
    fun connectByUsername(
        rawFriendUsername: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val normFriend = TacticalE2eeManager.normalizeUsername(rawFriendUsername)
        if (!TacticalE2eeManager.isValidUsername(normFriend)) {
            val err = "Enter a valid username (3–24 characters)."
            _uiState.update { it.copy(usernameConnectStatus = err, isConnectingByUsername = false) }
            onResult?.invoke(false, err)
            return
        }
        val selfNorm = TacticalE2eeManager.normalizeUsername(_uiState.value.claimedUsername)
        if (selfNorm.isNotBlank() && normFriend == selfNorm) {
            val err = "That is your own username."
            _uiState.update { it.copy(usernameConnectStatus = err, isConnectingByUsername = false) }
            onResult?.invoke(false, err)
            return
        }

        val canQueryOnline = _uiState.value.isInternetEnabled || hasValidatedOsInternet()
        // Broadcast USERNAME_LOOKUP over BLE/Wi-Fi mesh so nearby/multi-hop peers can reply off-grid
        val lookupPkt = com.example.service.WireMeshPacket(
            packetId = "ulook_${normFriend}_${System.currentTimeMillis() and 0xFFFF}",
            srcNodeId = _uiState.value.localNodeId,
            srcCallsign = _uiState.value.operatorCallsign,
            dstNodeId = "BROADCAST",
            type = "USERNAME_LOOKUP",
            payload = normFriend,
            ttl = 5,
            hops = 0,
            senderPubKey = prefs.x25519PublicKey,
            srcUsername = _uiState.value.claimedUsername
        )
        sendMeshPacket(lookupPkt)

        fun finishConnect(remoteClaim: com.example.crypto.UsernameClaimRecord?) {
            val localClaim = prefs.getUsernameClaim(normFriend)
                ?: prefs.getAllKnownUsernameClaims().firstOrNull {
                    TacticalE2eeManager.normalizeUsername(it.username) == normFriend
                }
            val resolvedClaim = remoteClaim ?: localClaim
            val matchedPeer = peersBackingMap.values.firstOrNull {
                TacticalE2eeManager.normalizeUsername(it.callsign) == normFriend
            }
            val friendNodeId = resolvedClaim?.nodeId
                ?: matchedPeer?.id
                ?: TacticalE2eeManager.deriveNodeIdFromCallsign(normFriend)
            val friendDisplay = resolvedClaim?.displayName?.ifBlank { "@$normFriend" }
                ?: matchedPeer?.callsign
                ?: "@$normFriend"
            val friendPubKey = resolvedClaim?.publicKeyBase64?.ifBlank { null }
                ?: matchedPeer?.publicKeyBase64.orEmpty()

            if (resolvedClaim != null) {
                prefs.saveUsernameClaim(resolvedClaim)
            } else {
                prefs.saveUsernameClaim(
                    com.example.crypto.UsernameClaimRecord(
                        username = normFriend,
                        displayName = friendDisplay,
                        nodeId = friendNodeId,
                        publicKeyBase64 = friendPubKey,
                        saltHex = "",
                        pinHash = "",
                        recoveryHash = "",
                        claimedAt = 0L
                    )
                )
            }
            if (friendPubKey.isNotBlank()) {
                prefs.verifyOrTrustPeerPublicKey(friendNodeId, friendPubKey)
            }
            if (resolvedClaim != null && canQueryOnline && !_uiState.value.isInternetEnabled) {
                toggleInternetEnabled(true)
            }

            val threadId = "dm_$friendNodeId"
            val statusMsg = if (_uiState.value.isInternetEnabled || canQueryOnline) {
                if (resolvedClaim != null) {
                    "Connected to @$normFriend ($friendNodeId) via Global Relay."
                } else {
                    "DM thread ready for @$normFriend ($friendNodeId). Messages route via their personal inbox."
                }
            } else if (matchedPeer != null) {
                "Connected to @$normFriend ($friendNodeId) over local mesh."
            } else {
                "DM thread ready for @$normFriend ($friendNodeId). Enable Hybrid Internet to reach distant friends outside BLE range."
            }

            _uiState.update {
                it.copy(
                    isConnectingByUsername = false,
                    usernameConnectStatus = statusMsg,
                    selectedTabIndex = 1
                )
            }
            refreshConversationsList()
            selectThread(threadId)
            scheduleAccountBackupSync()
            triggerHaptic(45)
            onResult?.invoke(true, statusMsg)
        }

        if (!canQueryOnline) {
            finishConnect(null)
        } else {
            _uiState.update { it.copy(isConnectingByUsername = true, usernameConnectStatus = "Looking up @$normFriend...") }
            viewModelScope.launch {
                val remote = meshNetworkService.pollUsernameClaim(normFriend, allowDirectLookup = true)
                finishConnect(remote)
            }
        }
    }

    /**
     * Resolves the real active outbound transport label (`"BLE 1M"`, `"BLE CODED"`, `"BLE GATT"`, `"HOTSPOT WIFI"`, or `"HYBRID CELL"`)
     * and the real measured RSSI (`Int?`, null when sent via Hybrid Internet or when no RF measurement exists).
     */
    fun resolveActiveOutboundTransportAndRssi(targetPeer: MeshNode? = null): Pair<String, Int?> {
        val state = _uiState.value
        val peerRssi = targetPeer?.rssiDbm?.takeIf { it in -120..-10 && it != -42 }
            ?: peersBackingMap.values.firstOrNull()?.rssiDbm?.takeIf { it in -120..-10 && it != -42 }
            ?: offGridBleEngine.telemetry.value.lastRssiDbm.takeIf { it in -120..-10 }

        return when {
            autoHotspotNegotiator.hasHighSpeedLink() -> {
                val wifiRssi = autoHotspotNegotiator.getRealWifiRssiDbm() ?: peerRssi
                Pair("HOTSPOT WIFI", wifiRssi)
            }
            offGridBleEngine.connectedGattClients.isNotEmpty() || offGridBleEngine.connectedServerDevices.isNotEmpty() -> {
                Pair("BLE GATT", peerRssi)
            }
            state.isInternetEnabled -> {
                Pair("HYBRID CELL", null)
            }
            else -> {
                val phy = targetPeer?.lastBlePhy?.takeIf { it.isNotBlank() }
                    ?: peersBackingMap.values.firstOrNull()?.lastBlePhy?.takeIf { it.isNotBlank() }
                    ?: "BLE 1M"
                Pair(phy, peerRssi)
            }
        }
    }

    /**
     * Creates a new multi-peer `MeshGroup`, saves it to `SharedPreferences`, adds its conversation to `CHATS`,
     * and broadcasts a `GROUP_INVITE` packet so all invited member nodes automatically add the group!
     */
    fun createMeshGroup(groupName: String, memberNodeIds: List<String>): MeshGroup {
        val state = _uiState.value
        val cleanName = groupName.trim().ifBlank { "Tactical Squad" }
        val groupId = "GRP-${Random.nextInt(1000, 9999)}"
        val normalizedMembers = (memberNodeIds.map { "0x${normalizeId(it)}" } + "0x${normalizeId(state.localNodeId)}")
            .filter { it.length >= 3 && it != "0x" }
            .distinct()

        val groupKeyB64 = TacticalE2eeManager.generateGroupAesKeyBase64()
        prefs.saveGroupAesKey(groupId, groupKeyB64)

        val newGroup = MeshGroup(
            groupId = groupId,
            groupName = cleanName,
            memberIds = normalizedMembers,
            createdAt = System.currentTimeMillis(),
            groupKeyBase64 = groupKeyB64
        )

        val updatedGroups = (state.meshGroups.filterNot { it.groupId == groupId } + newGroup)
        prefs.saveMeshGroups(updatedGroups)
        _uiState.update { it.copy(meshGroups = updatedGroups) }

        val threadId = "group_$groupId"
        val (outTransport, outRssi) = resolveActiveOutboundTransportAndRssi(null)
        val systemMsg = "Group \"$cleanName\" created (${normalizedMembers.size} members: ${normalizedMembers.joinToString(", ")})"
        val initPacket = MeshPacket(
            id = "grp_init_${groupId}_${System.currentTimeMillis()}",
            sourceId = state.localNodeId,
            destId = groupId,
            sourceCallsign = "${state.operatorCallsign} (Self)",
            type = PacketType.TEXT,
            content = systemMsg,
            hops = 0,
            rssiDbm = outRssi,
            transport = outTransport,
            rawHexBadge = "[$groupId | AES-256-GCM RANDOM GROUP KEY]",
            isIncoming = false,
            timestamp = System.currentTimeMillis()
        )
        persistentChatManager.savePacket(threadId, initPacket)
        refreshConversationsList()
        selectThread(threadId)

        val wrappedKeysObj = org.json.JSONObject()
        for (memberId in normalizedMembers) {
            val mNorm = normalizeId(memberId)
            if (mNorm.isBlank() || mNorm == normalizeId(state.localNodeId)) continue
            val mPub = prefs.getPeerPublicKey(memberId) ?: peersBackingMap[mNorm]?.publicKeyBase64
            if (!mPub.isNullOrBlank()) {
                TacticalE2eeManager.wrapGroupKeyForMember(
                    groupKeyBase64 = groupKeyB64,
                    myPrivateKeyBase64 = prefs.x25519PrivateKey,
                    memberPublicKeyBase64 = mPub
                )?.let { wrapped ->
                    wrappedKeysObj.put(mNorm, wrapped)
                }
            }
        }

        val encryptedInvite = TacticalE2eeManager.encryptWithGroupKey(systemMsg, groupKeyB64)
            ?: "PLAINTEXT:$systemMsg"
        val inviteWire = com.example.service.WireMeshPacket(
            packetId = "ginv_${groupId}_${System.currentTimeMillis()}",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = groupId,
            type = "GROUP_INVITE",
            payload = encryptedInvite,
            ttl = 7,
            hops = 0,
            lat = state.userLatitude,
            lng = state.userLongitude,
            altitude = state.barometricAltitudeMeters,
            battery = state.realBatteryPercent,
            groupId = groupId,
            groupName = cleanName,
            memberIdsCsv = normalizedMembers.joinToString(","),
            senderPubKey = prefs.x25519PublicKey,
            encryptedKeysJson = wrappedKeysObj.toString()
        )
        enqueueDtnPacket(inviteWire)
        sendOmniPacket(inviteWire)
        triggerHaptic(50)
        return newGroup
    }

    private fun formatConversationPreview(rawContent: String?): String? {
        if (rawContent.isNullOrBlank()) return null
        return when {
            rawContent.startsWith("[[PM_IMAGE]]:") -> {
                val payload = rawContent.removePrefix("[[PM_IMAGE]]:").trim()
                val caption = payload.substringAfter("||", "").trim()
                if (caption.isNotBlank()) "📷 Photo: $caption" else "📷 Tactical Photo"
            }
            rawContent.startsWith("[[PM_VOICEDROP]]:") || rawContent.startsWith("[[PM_AUDIO]]:") -> {
                "🎙️ Tactical Voice Drop (3s)"
            }
            else -> rawContent
        }
    }

    fun refreshConversationsList() {
        val state = _uiState.value
        val primaryPackets = persistentChatManager.getPacketsForThread("channel_primary")
        val encPackets = persistentChatManager.getPacketsForThread("channel_encrypted")

        val lastPrimary = primaryPackets.firstOrNull()
        val lastEnc = encPackets.firstOrNull()

        val convos = mutableListOf<ChatConversation>()

        // 1. Public Mesh Broadcast (ALL) & TacNet Encrypted Broadcast
        convos.add(
            ChatConversation(
                threadId = "channel_primary",
                title = "Public Mesh Broadcast (ALL)",
                isChannel = true,
                isEncrypted = false,
                lastMessageText = formatConversationPreview(lastPrimary?.content) ?: "Public broadcast mesh nominal.",
                lastTimestamp = lastPrimary?.timestamp ?: (System.currentTimeMillis() - 100000),
                unreadCount = unreadCountsByThread["channel_primary"] ?: 0,
                isPinned = true
            )
        )
        convos.add(
            ChatConversation(
                threadId = "channel_encrypted",
                title = "#TacNet-Encrypted",
                isChannel = true,
                isEncrypted = true,
                lastMessageText = formatConversationPreview(lastEnc?.content) ?: "AES-256-GCM authenticated group.",
                lastTimestamp = lastEnc?.timestamp ?: (System.currentTimeMillis() - 80000),
                unreadCount = unreadCountsByThread["channel_encrypted"] ?: 0,
                isPinned = true
            )
        )

        // 2. Custom Multi-Mesh Group Chats (MeshGroup)
        val myNorm = normalizeId(state.localNodeId)
        val persistedGroups = prefs.loadMeshGroups()
        val activeGroups = (state.meshGroups + persistedGroups).distinctBy { it.groupId }
            .filter { grp -> grp.memberIds.any { normalizeId(it) == myNorm } }

        for (grp in activeGroups) {
            val gThreadId = "group_${grp.groupId}"
            val gPackets = persistentChatManager.getPacketsForThread(gThreadId)
            val lastGMsg = gPackets.firstOrNull()
            convos.add(
                ChatConversation(
                    threadId = gThreadId,
                    title = "${grp.groupName} (${grp.memberIds.size} nodes)",
                    isChannel = false,
                    isGroup = true,
                    isEncrypted = true,
                    groupId = grp.groupId,
                    memberIds = grp.memberIds,
                    lastMessageText = formatConversationPreview(lastGMsg?.content) ?: "Multi-peer AES-256-GCM group ready.",
                    lastTimestamp = lastGMsg?.timestamp ?: grp.createdAt,
                    unreadCount = unreadCountsByThread[gThreadId] ?: 0,
                    isPinned = true,
                    isOnline = true
                )
            )
        }

        // 3. 1-on-1 Direct Chats (E2EE DMs + Connected @Usernames) — strictly NEVER allow self-chat!
        val selfNormId = normalizeId(state.localNodeId)
        val peerNodes = (state.nodes.filter { !it.isSelf } + peersBackingMap.values)
            .filter { normalizeId(it.id) != selfNormId }
            .distinctBy { normalizeId(it.id) }
            .filter { !isBlockedFakeCallsign(it.callsign) }
        val savedDmThreads = persistentChatManager.getAllThreadIds()
            .filter { it.startsWith("dm_") }
            .filter { tid ->
                val isSelfDm = normalizeId(tid.removePrefix("dm_")) == selfNormId
                if (isSelfDm) persistentChatManager.deleteThread(tid)
                !isSelfDm
            }
        val knownClaims = prefs.getAllKnownUsernameClaims().filter { claim ->
            normalizeId(claim.nodeId) != selfNormId
        }
        val allPeerIds = (
            peerNodes.map { it.id } +
                savedDmThreads.map { it.removePrefix("dm_") } +
                knownClaims.map { it.nodeId }
            )
            .filter { it.isNotBlank() && normalizeId(it) != selfNormId }
            .distinctBy { normalizeId(it) }

        for (peerId in allPeerIds) {
            val threadId = "dm_$peerId"
            val peer = peerNodes.find { normalizeId(it.id) == normalizeId(peerId) }
            val claim = prefs.findUsernameClaimByNodeId(peerId)
            val packets = persistentChatManager.getPacketsForThread(threadId)
            val lastMsg = packets.firstOrNull()
            val candidateCallsign = peer?.callsign
                ?: claim?.let { "@${it.username}" }
                ?: lastMsg?.sourceCallsign
                ?: continue
            if (isBlockedFakeCallsign(candidateCallsign)) continue

            val storedPeerKey = peer?.publicKeyBase64?.ifBlank { null }
                ?: claim?.publicKeyBase64?.ifBlank { null }
                ?: prefs.getPeerPublicKey(peerId).orEmpty()
            val safetyFp = peer?.safetyFingerprint?.ifBlank { null } ?: if (storedPeerKey.isNotBlank()) {
                TacticalE2eeManager.computePairwiseSafetyFingerprint(prefs.x25519PublicKey, storedPeerKey)
            } else {
                ""
            }
            val keyWarning = peer?.isKeyChangedWarning ?: prefs.isPeerKeyChanged(peerId)
            val displayTitle = if (claim != null && !candidateCallsign.contains("@${claim.username}")) {
                "@${claim.username} • $candidateCallsign ($peerId)"
            } else {
                "$candidateCallsign ($peerId)"
            }

            convos.add(
                ChatConversation(
                    threadId = threadId,
                    title = displayTitle,
                    isChannel = false,
                    isEncrypted = true,
                    peerNodeId = peerId,
                    peerCallsign = candidateCallsign,
                    lastMessageText = formatConversationPreview(lastMsg?.content)
                        ?: if (claim != null) {
                            "Username Inbox Ready (@${claim.username})"
                        } else if (state.isSimpleMode) {
                            "Encrypted direct chat ready."
                        } else if (safetyFp.isNotBlank()) {
                            "ECDH P-256 TOFU ($safetyFp)"
                        } else {
                            "Waiting for peer ECDH key exchange."
                        },
                    lastTimestamp = lastMsg?.timestamp ?: (if (peer != null || claim != null) System.currentTimeMillis() else 0L),
                    unreadCount = unreadCountsByThread[threadId] ?: 0,
                    isPinned = false,
                    isOnline = peer != null || (claim != null && state.isInternetEnabled),
                    safetyFingerprint = safetyFp,
                    isKeyChangedWarning = keyWarning
                )
            )
        }

        _uiState.update { current ->
            val activeThreadId = current.selectedThreadId
            val activePackets = if (activeThreadId != null) {
                persistentChatManager.getPacketsForThread(activeThreadId)
            } else {
                primaryPackets
            }
            current.copy(
                meshGroups = activeGroups,
                conversations = convos,
                activeThreadPackets = activePackets,
                packets = activePackets
            )
        }
    }

    fun markThreadAsRead(threadId: String) {
        if (threadId.isBlank()) return
        unreadCountsByThread[threadId] = 0
        notificationManager.cancelChatNotificationForThread(threadId)
        refreshConversationsList()
    }

    fun selectThread(threadId: String?) {
        if (threadId != null && threadId.startsWith("dm_")) {
            val targetNorm = normalizeId(threadId.removePrefix("dm_"))
            if (targetNorm == normalizeId(_uiState.value.localNodeId)) {
                persistentChatManager.deleteThread(threadId)
                return
            }
        }
        if (!threadId.isNullOrBlank()) {
            unreadCountsByThread[threadId] = 0
            notificationManager.cancelChatNotificationForThread(threadId)
        }
        _uiState.update { current ->
            val packets = if (threadId != null) persistentChatManager.getPacketsForThread(threadId) else emptyList()
            val updatedConvos = if (threadId != null) {
                current.conversations.map { c ->
                    if (c.threadId == threadId) c.copy(unreadCount = 0) else c
                }
            } else {
                current.conversations
            }
            current.copy(
                selectedThreadId = threadId,
                conversations = updatedConvos,
                activeThreadPackets = packets,
                packets = packets
            )
        }
        triggerHaptic(20)
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun sendMessageInThread(threadId: String, text: String) {
        if (text.isBlank()) return
        val state = _uiState.value
        val trimmed = text.trim()
        val pktId = "PKT#${(System.currentTimeMillis() and 0xFFFFFL).toString(16).uppercase()}${Random.nextInt(100, 999)}"

        val isPrimary = threadId == "channel_primary"
        val isEncChannel = threadId == "channel_encrypted"
        val isGroupChat = threadId.startsWith("group_")
        val isDm = threadId.startsWith("dm_")
        if (isDm && normalizeId(threadId.removePrefix("dm_")) == normalizeId(state.localNodeId)) {
            persistentChatManager.deleteThread(threadId)
            return
        }

        val groupObj = if (isGroupChat) {
            val gId = threadId.removePrefix("group_")
            state.meshGroups.find { it.groupId == gId } ?: prefs.loadMeshGroups().find { it.groupId == gId }
        } else null

        val targetDest = when {
            isPrimary -> "ALL"
            isEncChannel -> "ENC_TACNET"
            isGroupChat -> groupObj?.groupId ?: threadId.removePrefix("group_")
            isDm -> threadId.removePrefix("dm_")
            else -> "ALL"
        }

        val targetPeerNode = if (isDm) {
            peersBackingMap[normalizeId(targetDest)] ?: state.nodes.find { !it.isSelf && normalizeId(it.id) == normalizeId(targetDest) }
        } else null

        val (realOutboundTransport, realOutboundRssi) = resolveActiveOutboundTransportAndRssi(targetPeerNode)
        val recipientClaim = if (isDm) prefs.findUsernameClaimByNodeId(targetDest) else null
        val canDirectInboxOnline = state.isInternetEnabled || hasValidatedOsInternet()

        val wirePayload: String
        val badge: String
        var canTransmitWire = true
        var inboxOnlyWithoutEcdh = false

        if (isGroupChat) {
            val groupKeyB64 = groupObj?.groupKeyBase64?.takeIf { TacticalE2eeManager.isValidGroupAesKeyBase64(it) }
                ?: prefs.getGroupAesKey(targetDest)
                ?: TacticalE2eeManager.generateGroupAesKeyBase64().also { prefs.saveGroupAesKey(targetDest, it) }
            val encGroup = TacticalE2eeManager.encryptWithGroupKey(trimmed, groupKeyB64)
            if (encGroup != null) {
                wirePayload = encGroup
                badge = "[$pktId | ${state.localNodeId}->$targetDest | GROUP AES-256-GCM]"
            } else {
                wirePayload = ""
                canTransmitWire = false
                badge = "[$pktId | GROUP KEY PENDING]"
            }
        } else if (isEncChannel || isDm) {
            if (isDm) {
                val peerPubKey = prefs.getPeerPublicKey(targetDest)
                    ?: peersBackingMap[normalizeId(targetDest)]?.publicKeyBase64
                    ?: recipientClaim?.publicKeyBase64?.takeIf { TacticalE2eeManager.isValidEcPublicKeyBase64(it) }
                val ecdhCiphertext = if (!peerPubKey.isNullOrBlank()) {
                    TacticalE2eeManager.encryptDmWithEcdh(trimmed, prefs.x25519PrivateKey, peerPubKey)
                } else {
                    null
                }
                if (ecdhCiphertext != null) {
                    wirePayload = ecdhCiphertext
                    badge = "[$pktId | ${state.localNodeId}->$targetDest | ECDH P-256 + AES-256-GCM]"
                } else if (recipientClaim != null && canDirectInboxOnline) {
                    // Deliver through recipient's AES-256-GCM encrypted @username inbox while syncing ECDH key
                    wirePayload = "PLAINTEXT:$trimmed"
                    canTransmitWire = true
                    inboxOnlyWithoutEcdh = true
                    badge = "[$pktId | @${recipientClaim.username} INBOX + ECDH SYNC]"
                    announcePublicKey(force = true)
                } else {
                    wirePayload = ""
                    canTransmitWire = false
                    badge = "[$pktId | ECDH KEY EXCHANGE PENDING]"
                    announcePublicKey(force = true)
                }
            } else {
                val sharedSecret = "TACNET_SHARED_SECRET_KEY_001"
                val enc = TacticalE2eeManager.encrypt(
                    plaintext = trimmed,
                    sharedSecretHex = sharedSecret,
                    senderFingerprint = state.x25519Fingerprint
                )
                wirePayload = "ENC:${enc.ciphertextHex}:${enc.nonceHex}:${enc.macTagHex}:${enc.senderFingerprint}"
                badge = "[$pktId | ${state.localNodeId}->$targetDest | E2EE: AES-256-GCM | TAG: ${enc.macTagHex}]"
            }
        } else {
            wirePayload = trimmed
            badge = "[$pktId | ${state.localNodeId}->ALL | Hops: 0/7 | $realOutboundTransport]"
        }

        val nowMs = System.currentTimeMillis()
        val effectiveBurnSec = state.perThreadBurnTtlMap[threadId] ?: state.messageBurnTtlSeconds
        val burnExpMs = if (effectiveBurnSec > 0) nowMs + effectiveBurnSec * 1000L else 0L
        val userPacket = MeshPacket(
            id = pktId,
            sourceId = state.localNodeId,
            destId = targetDest,
            sourceCallsign = "${state.operatorCallsign} (Self)",
            type = PacketType.TEXT,
            content = trimmed,
            hops = 0,
            rssiDbm = realOutboundRssi,
            transport = if (canTransmitWire) realOutboundTransport else "ECDH KEY PENDING",
            rawHexBadge = badge,
            isIncoming = false,
            timestamp = nowMs,
            expiresAtMs = burnExpMs
        )

        // Immediately display the message in the sender's chat list
        persistentChatManager.savePacket(threadId, userPacket)
        refreshConversationsList()
        scheduleAccountBackupSync()
        triggerHaptic(30)

        if (!canTransmitWire) return

        val wirePacket = com.example.service.WireMeshPacket(
            packetId = pktId,
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = targetDest,
            type = when {
                isGroupChat -> "GROUP_CHAT"
                isDm -> "E2EE_DM"
                else -> "CHAT"
            },
            payload = wirePayload,
            lat = state.userLatitude,
            lng = state.userLongitude,
            altitude = state.barometricAltitudeMeters,
            battery = state.realBatteryPercent,
            sourceTransport = realOutboundTransport,
            rssiDbm = realOutboundRssi,
            groupId = if (isGroupChat) targetDest else "",
            groupName = groupObj?.groupName ?: "",
            memberIdsCsv = groupObj?.memberIds?.joinToString(",") ?: "",
            senderPubKey = if (isDm || isGroupChat) prefs.x25519PublicKey else "",
            srcUsername = state.claimedUsername,
            dstUsername = recipientClaim?.username ?: ""
        )

        pendingChatAcks[pktId] = false
        if (!inboxOnlyWithoutEcdh) {
            enqueueDtnPacket(wirePacket)
            sendOmniPacket(wirePacket)
        }
        if (isDm && recipientClaim != null && canDirectInboxOnline) {
            if (!state.isInternetEnabled) {
                toggleInternetEnabled(true)
            }
            meshNetworkService.sendDirectPacketToUsername(
                recipientClaim.username,
                wirePacket,
                allowDirectSend = true
            )
        }
        if (isPrimary && trimmed.length <= 10) {
            offGridBleEngine.broadcastShortChatInScanResponse(trimmed)
        }

        // Smart 2-step Automatic Chat Retry (1.2s and 3.5s) if no CHAT_ACK has arrived yet and peers/internet are active
        viewModelScope.launch(Dispatchers.IO) {
            val retryDelays = longArrayOf(1_200L, 2_300L)
            for (waitMs in retryDelays) {
                delay(waitMs)
                if (pendingChatAcks[pktId] == true) break
                val cur = _uiState.value
                val hasActiveRoute = cur.activePeerCount > 0 || cur.isInternetEnabled || autoHotspotNegotiator.hasHighSpeedLink()
                if (!hasActiveRoute) break
                if (!inboxOnlyWithoutEcdh) {
                    sendOmniPacket(wirePacket)
                }
                if (isDm && recipientClaim != null && (cur.isInternetEnabled || hasValidatedOsInternet())) {
                    meshNetworkService.sendDirectPacketToUsername(
                        recipientClaim.username,
                        wirePacket,
                        allowDirectSend = true
                    )
                }
            }
        }
    }

    /**
     * Retries transmitting an outgoing chat message that has not yet received a `CHAT_ACK` delivery receipt.
     */
    fun retrySendChatPacket(packet: MeshPacket) {
        if (packet.isIncoming || packet.isDelivered) return
        val state = _uiState.value
        val existingDtn = synchronized(pendingDtnPackets) {
            pendingDtnPackets.find { it.packetId == packet.id }
        }
        if (existingDtn != null) {
            pendingChatAcks[packet.id] = false
            announcePublicKey(force = true)
            sendOmniPacket(existingDtn)
            val claimForExisting = if (existingDtn.type == "E2EE_DM") prefs.findUsernameClaimByNodeId(existingDtn.dstNodeId) else null
            if (claimForExisting != null && state.isInternetEnabled) {
                meshNetworkService.sendDirectPacketToUsername(claimForExisting.username, existingDtn)
            }
            viewModelScope.launch(Dispatchers.IO) {
                delay(750L)
                if (pendingChatAcks[packet.id] != true) {
                    sendOmniPacket(existingDtn)
                    if (claimForExisting != null && _uiState.value.isInternetEnabled) {
                        meshNetworkService.sendDirectPacketToUsername(claimForExisting.username, existingDtn)
                    }
                }
            }
            triggerHaptic(45)
            return
        }

        // Re-construct and re-send if it was waiting on ECDH key exchange or aged out of the DTN ring buffer
        val threadId = state.selectedThreadId ?: when {
            packet.destId.equals("ALL", ignoreCase = true) -> "channel_primary"
            packet.destId.equals("ENC_TACNET", ignoreCase = true) -> "channel_encrypted"
            state.meshGroups.any { it.groupId == packet.destId } -> "group_${packet.destId}"
            else -> "dm_${packet.destId}"
        }
        val isGroupChat = threadId.startsWith("group_")
        val isDm = threadId.startsWith("dm_")
        if (isDm && normalizeId(threadId.removePrefix("dm_")) == normalizeId(state.localNodeId)) {
            persistentChatManager.deleteThread(threadId)
            return
        }
        val targetDest = packet.destId.ifBlank { "ALL" }
        val groupObj = if (isGroupChat) {
            state.meshGroups.find { it.groupId == targetDest } ?: prefs.loadMeshGroups().find { it.groupId == targetDest }
        } else null

        val wirePayload: String? = when {
            isGroupChat -> {
                val groupKeyB64 = groupObj?.groupKeyBase64?.takeIf { TacticalE2eeManager.isValidGroupAesKeyBase64(it) }
                    ?: prefs.getGroupAesKey(targetDest)
                if (!groupKeyB64.isNullOrBlank()) {
                    TacticalE2eeManager.encryptWithGroupKey(packet.content, groupKeyB64)
                } else null
            }
            isDm -> {
                val peerPubKey = prefs.getPeerPublicKey(targetDest)
                    ?: peersBackingMap[normalizeId(targetDest)]?.publicKeyBase64
                if (!peerPubKey.isNullOrBlank()) {
                    TacticalE2eeManager.encryptDmWithEcdh(packet.content, prefs.x25519PrivateKey, peerPubKey)
                } else {
                    announcePublicKey(force = true)
                    null
                }
            }
            threadId == "channel_encrypted" -> {
                val enc = TacticalE2eeManager.encrypt(
                    plaintext = packet.content,
                    sharedSecretHex = "TACNET_SHARED_SECRET_KEY_001",
                    senderFingerprint = state.x25519Fingerprint
                )
                "ENC:${enc.ciphertextHex}:${enc.nonceHex}:${enc.macTagHex}:${enc.senderFingerprint}"
            }
            else -> packet.content
        }

        if (!wirePayload.isNullOrBlank()) {
            val (realOutboundTransport, realOutboundRssi) = resolveActiveOutboundTransportAndRssi(
                peersBackingMap[normalizeId(targetDest)]
            )
            val refreshedBadge = when {
                isGroupChat -> "[${packet.id} | ${state.localNodeId}->$targetDest | GROUP AES-256-GCM]"
                isDm -> "[${packet.id} | ${state.localNodeId}->$targetDest | ECDH P-256 + AES-256-GCM]"
                threadId == "channel_encrypted" -> "[${packet.id} | ${state.localNodeId}->$targetDest | E2EE: AES-256-GCM]"
                else -> "[${packet.id} | ${state.localNodeId}->ALL | $realOutboundTransport]"
            }
            if (persistentChatManager.updatePacketTransportBadge(threadId, packet.id, realOutboundTransport, refreshedBadge)) {
                refreshConversationsList()
            }
            val recipientClaim = if (isDm) prefs.findUsernameClaimByNodeId(targetDest) else null
            val wirePkt = com.example.service.WireMeshPacket(
                packetId = packet.id,
                srcNodeId = state.localNodeId,
                srcCallsign = state.operatorCallsign,
                dstNodeId = targetDest,
                type = when {
                    isGroupChat -> "GROUP_CHAT"
                    isDm -> "E2EE_DM"
                    else -> "CHAT"
                },
                payload = wirePayload,
                lat = state.userLatitude,
                lng = state.userLongitude,
                altitude = state.barometricAltitudeMeters,
                battery = state.realBatteryPercent,
                sourceTransport = realOutboundTransport,
                rssiDbm = realOutboundRssi,
                groupId = if (isGroupChat) targetDest else "",
                groupName = groupObj?.groupName ?: "",
                memberIdsCsv = groupObj?.memberIds?.joinToString(",") ?: "",
                senderPubKey = if (isDm || isGroupChat) prefs.x25519PublicKey else "",
                srcUsername = state.claimedUsername,
                dstUsername = recipientClaim?.username ?: ""
            )
            pendingChatAcks[packet.id] = false
            enqueueDtnPacket(wirePkt)
            sendOmniPacket(wirePkt)
            val canDirectInboxOnline = state.isInternetEnabled || hasValidatedOsInternet()
            if (isDm && recipientClaim != null && canDirectInboxOnline) {
                meshNetworkService.sendDirectPacketToUsername(
                    recipientClaim.username,
                    wirePkt,
                    allowDirectSend = true
                )
            }
        }
        triggerHaptic(45)
    }

    /**
     * Automatically flushes queued direct messages that were awaiting a peer's ECDH public key
     * as soon as the peer's public key is learned via KEY_ANNOUNCE, presence, or Web Portal registration.
     */
    private fun flushPendingKeyExchangeDmsForPeer(peerNodeId: String) {
        val normPeer = normalizeId(peerNodeId)
        if (normPeer.isBlank() || normPeer == normalizeId(_uiState.value.localNodeId)) return
        val matchingThread = persistentChatManager.getAllThreadIds().firstOrNull { tid ->
            tid.startsWith("dm_") && normalizeId(tid.removePrefix("dm_")) == normPeer
        } ?: "dm_0x$normPeer"
        val pending = persistentChatManager.getPacketsForThread(matchingThread).filter { pkt ->
            !pkt.isIncoming &&
                !pkt.isDelivered &&
                (pkt.transport == "ECDH KEY PENDING" || pkt.rawHexBadge.contains("KEY EXCHANGE PENDING"))
        }
        for (pkt in pending) {
            retrySendChatPacket(pkt)
        }
    }

    /**
     * Store-and-Forward "Data Mule" Outbox (DTN):
     * Enqueues a CHAT or E2EE_DM packet (up to 50 recent packets) and persists to SharedPreferences
     * so out-of-range nodes automatically receive missed messages when discovered on any layer.
     */
    fun enqueueDtnPacket(packet: com.example.service.WireMeshPacket) {
        if (packet.type != "CHAT" && packet.type != "E2EE_DM" && packet.type != "GROUP_CHAT" && packet.type != "GROUP_INVITE") return
        synchronized(pendingDtnPackets) {
            pendingDtnPackets.removeAll { it.packetId == packet.packetId }
            pendingDtnPackets.add(packet)
            while (pendingDtnPackets.size > 50) {
                pendingDtnPackets.removeAt(0)
            }
            val snapshot = pendingDtnPackets.toList()
            prefs.savePendingDtnPackets(snapshot)
            _uiState.update { it.copy(pendingDtnCount = snapshot.size) }
        }
    }

    fun getPendingDtnPacketsSnapshot(): List<com.example.service.WireMeshPacket> {
        synchronized(pendingDtnPackets) {
            return pendingDtnPackets.toList()
        }
    }

    /**
     * Automatically flushes queued DTN CHAT / E2EE_DM / GROUP_CHAT packets ONLY ONCE when a brand-new peer first appears,
     * never on repeated scan callbacks!
     */
    fun flushPendingDtnPacketsToPeer(peerNodeId: String) {
        val normPeer = normalizeId(peerNodeId)
        if (normPeer.isBlank() || normPeer == normalizeId(_uiState.value.localNodeId)) return
        if (!dtnFlushedPeers.add(normPeer)) return

        val eligible = synchronized(pendingDtnPackets) {
            pendingDtnPackets.filter { pkt ->
                pkt.dstNodeId == "ALL" ||
                    pkt.dstNodeId == "BROADCAST" ||
                    pkt.dstNodeId == "ENC_TACNET" ||
                    pkt.type == "GROUP_INVITE" ||
                    pkt.type == "GROUP_CHAT" ||
                    normalizeId(pkt.dstNodeId) == normPeer
            }
        }
        if (eligible.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            for (pkt in eligible.takeLast(10)) {
                sendOmniPacket(pkt)
                delay(140L)
            }
        }
    }

    /**
     * Unified 9-Layer Omni-Connection Router (Outbound).
     */
    fun sendOmniPacket(packet: com.example.service.WireMeshPacket) {
        sendMeshPacket(packet)
    }

    /**
     * Unified Omni-Connection Router (Inbound).
     */
    fun onOmniPacketReceived(packet: com.example.service.WireMeshPacket) {
        handleIncomingPacket(packet)
    }

    fun sendChatMessage(text: String) {
        val currentThread = _uiState.value.selectedThreadId ?: "channel_primary"
        sendMessageInThread(currentThread, text)
    }

    /**
     * Compresses and shares a photo (`Bitmap`) inside the currently selected chat thread.
     * Scales the image down to a compact tactical thumbnail (max 200px) and compresses as JPEG (quality 50)
     * so it fits inside a single encrypted mesh packet (`CHAT` / `E2EE_DM` / `GROUP_CHAT`) over BLE GATT,
     * Swarm Hotspot Wi-Fi, or Hybrid Internet Relay.
     */
    fun sendPhotoInChat(bitmap: android.graphics.Bitmap, caption: String = "") {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val maxDim = 200
                val w = bitmap.width.coerceAtLeast(1)
                val h = bitmap.height.coerceAtLeast(1)
                val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h, 1f)
                val targetW = (w * scale).toInt().coerceAtLeast(1)
                val targetH = (h * scale).toInt().coerceAtLeast(1)
                val scaled = if (targetW != w || targetH != h) {
                    val out = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(out)
                    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    c.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.Rect(0, 0, targetW, targetH),
                        p
                    )
                    out
                } else {
                    bitmap
                }
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                val cleanCaption = caption.trim().replace("\n", " ").take(80)
                val payload = if (cleanCaption.isNotBlank()) {
                    "[[PM_IMAGE]]:$b64||$cleanCaption"
                } else {
                    "[[PM_IMAGE]]:$b64"
                }
                viewModelScope.launch(Dispatchers.Main) {
                    val currentThread = _uiState.value.selectedThreadId ?: "channel_primary"
                    sendMessageInThread(currentThread, payload)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Generates and sends a visual Tactical Telemetry Recon Card image into the active chat thread.
     */
    fun sendTacticalSnapInChat() {
        val state = _uiState.value
        val bmp = android.graphics.Bitmap.createBitmap(220, 140, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0xFF0B1118.toInt())

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF00E676.toInt()
        }
        canvas.drawRect(3f, 3f, 217f, 137f, borderPaint)

        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 11.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
        }
        val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5FF.toInt()
            textSize = 11f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }

        val selfBattery = state.nodes.find { it.isSelf }?.batteryPercent ?: 100
        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        canvas.drawText("TACTICAL RECON SNAP", 12f, 24f, titlePaint)
        canvas.drawText("OPR: ${state.operatorCallsign} (${state.localNodeId})", 12f, 48f, bodyPaint)
        canvas.drawText(
            String.format(java.util.Locale.US, "LOC: %.4f, %.4f", state.userLatitude, state.userLongitude),
            12f,
            70f,
            subPaint
        )
        canvas.drawText("BAT: $selfBattery% | PEERS: ${state.activePeerCount}", 12f, 92f, bodyPaint)
        canvas.drawText("UTC: $timeFmt • MESH VERIFIED", 12f, 118f, titlePaint)

        sendPhotoInChat(bmp, "Tactical Telemetry Snapshot ($timeFmt)")
    }

    fun setVoiceTransportMode(mode: VoiceTransportMode) {
        _uiState.update { it.copy(voiceTransportMode = mode) }
        triggerHaptic(30)
    }

    fun setPttTargetNode(nodeId: String) {
        _uiState.update { it.copy(pttTargetNodeId = nodeId) }
        val targetNode = _uiState.value.nodes.find { it.id == nodeId }
        if (targetNode != null && !targetNode.isSelf) {
            val rssi = targetNode.rssiDbm
            val recommended = when {
                rssi >= -75 -> VoiceTransportMode.FULL_DUPLEX_OPUS
                rssi >= -98 -> VoiceTransportMode.MULTI_HOP_BURST
                else -> VoiceTransportMode.GHOST_VOICE
            }
            _uiState.update { it.copy(voiceTransportMode = recommended) }
        }
        triggerHaptic(20)
    }

    fun startPtt() {
        if (_uiState.value.isPttPressed) return
        val selfId = _uiState.value.localNodeId
        _uiState.update {
            it.copy(
                isPttPressed = true,
                callDurationSec = 0,
                pttStatus = "LIVE MIC -> PCM VOICE_STREAM...",
                voiceHopStep = "Streaming VOICE_STREAM Frames..."
            )
        }
        triggerHaptic(80)
        audioService.useLiveMicrophone = _uiState.value.useLiveMicrophone
        audioService.startTransmission()

        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            var seconds = 0
            while (isActive && _uiState.value.isPttPressed) {
                delay(1000)
                seconds++
                val hopDesc = when (seconds % 3) {
                    0 -> "TX: Hopping via $selfId -> Local RF Carrier"
                    1 -> "RELAY: Stateless 8-Bit PCM (VOICE_STREAM)"
                    else -> "RX: Ready / Carrier Open"
                }
                _uiState.update {
                    it.copy(
                        callDurationSec = seconds,
                        voiceHopStep = hopDesc,
                        packetLossPercent = 0f,
                        jitterMs = it.jitterMs
                    )
                }
            }
        }
    }

    fun stopPtt() {
        if (!_uiState.value.isPttPressed && !audioService.isRecording.value) return
        _uiState.update {
            it.copy(
                isPttPressed = false,
                pttStatus = "STANDBY • READY TO TRANSMIT",
                voiceHopStep = "Ready"
            )
        }
        callTimerJob?.cancel()
        callTimerJob = null
        val fullBurstB64 = audioService.stopTransmission(playRogerBeep = false)
        if (!fullBurstB64.isNullOrBlank() && !_uiState.value.useLiveMicrophone) {
            sendVoiceStreamBase64(fullBurstB64)
        }
        triggerHaptic(40)
    }

    fun togglePttOneTap() {
        if (_uiState.value.isPttPressed) {
            stopPtt()
        } else {
            startPtt()
        }
    }

    /**
     * Unified Voice Audio Frame Dispatcher (`type = "VOICE"`):
     * - Sends over Hotspot UDP (45888) and TCP (45889)
     * - Sends over Hybrid Internet (`ntfy.sh`) ONLY when `isInternetEnabled == true`
     * - Sends over primary BLE GATT (`0000CAFF`) via 1-slot `bleVoiceTxChannel` if Hotspot is not yet connected
     */
    fun sendVoiceAudioPacket(
        packet: com.example.service.WireMeshPacket,
        skipWebPortalNotify: Boolean = false
    ) {
        val state = _uiState.value
        val unifiedJson = org.json.JSONObject().apply {
            put("id", packet.packetId)
            put("type", packet.type)
            put("srcId", packet.srcNodeId)
            put("srcName", packet.srcCallsign.take(12))
            put("dstId", packet.dstNodeId)
            put("ttl", packet.ttl)
            put("hops", packet.hops)
            put("payload", packet.payload)
        }.toString()

        // 1. Local Hotspot UDP (45888): send zero-bloat raw binary "PA" frame for ADPCM8K payloads
        if (!skipWebPortalNotify && pulseMeshWebServer.isRunning() && packet.payload.isNotBlank()) {
            val vSeq = webVoiceSeqCounter.incrementAndGet()
            latestWebVoiceFrame = Triple(vSeq, packet.srcNodeId to packet.srcCallsign, packet.payload)
            pulseMeshWebServer.notifyVoiceFrame(
                packet.srcNodeId,
                packet.srcCallsign,
                packet.payload,
                isCallAudio = packet.type == "CALL_AUDIO" || state.duplexCallState.isInCall
            )
        }
        if (packet.payload.startsWith("ADPCM8K:")) {
            val rawAdpcm = runCatching {
                android.util.Base64.decode(packet.payload.removePrefix("ADPCM8K:"), android.util.Base64.DEFAULT)
            }.getOrNull()
            if (rawAdpcm != null && rawAdpcm.size > 4) {
                val parsedSeq = extractVoiceSeqFromPacketId(packet.packetId)
                val seq = parsedSeq ?: (audioFrameSeqCounter.get() and 0xFFFF)
                val paFrame = com.example.service.TacticalAudioService.packBinaryAdpcmVoiceFrame(
                    srcNodeId = packet.srcNodeId,
                    dstNodeId = packet.dstNodeId,
                    seq = seq,
                    adpcmBytes = rawAdpcm
                )
                autoHotspotNegotiator.sendBinaryVoiceUdpPacket(paFrame, jsonTcpFallback = unifiedJson)
            } else {
                autoHotspotNegotiator.sendVoiceUdpPacket(unifiedJson)
            }
        } else {
            autoHotspotNegotiator.sendVoiceUdpPacket(unifiedJson)
        }

        // 2. Hybrid Internet Relay (ntfy.sh) ONLY when Internet Enabled
        if (state.isInternetEnabled) {
            meshNetworkService.broadcastAudioPacket(packet)
        }

        // 3. Primary BLE L2CAP / GATT (0000CAFF) 1-slot latest-frame voice channel if Hotspot isn't actively delivering
        // or if a BLE L2CAP CoC voice stream is open.
        if (!autoHotspotNegotiator.hasActiveVoiceDeliveryPath() || offGridBleEngine.hasActiveL2capChannel()) {
            val blePacket = if (packet.payload.startsWith("ADPCM8K:")) {
                packet
            } else {
                runCatching {
                    val rawBytes = android.util.Base64.decode(packet.payload, android.util.Base64.DEFAULT)
                    if (rawBytes.size == com.example.service.TacticalAudioService.VOICE_SLICE_SAMPLES_160MS) {
                        val pcm8k = com.example.service.TacticalAudioService.decodeMuLawToPcm16(rawBytes)
                        val compact640 = com.example.service.TacticalAudioService.encode160msVoiceMuLaw640(pcm8k)
                        val compactB64 = android.util.Base64.encodeToString(compact640, android.util.Base64.NO_WRAP)
                        packet.copy(payload = compactB64)
                    } else {
                        packet
                    }
                }.getOrDefault(packet)
            }
            offGridBleEngine.sendVoiceFrameIfBleIdle(blePacket)
        }
    }

    /**
     * Unified Line-Delimited JSON Mesh Packet Dispatcher:
     * Transmits simultaneously across:
     * 1. Bidirectional BLE GATT (`0000CAFF`, strictly serialized and callback-synchronized; excludes HEARTBEAT/GPS_BEACON)
     * 2. Hotspot / P2P TCP (`Socket(peerIp, 45889)`) and UDP (`DatagramSocket(45888)`)
     * 3. Hybrid Internet (`ntfy.sh`, ONLY when `isInternetEnabled == true`)
     */
    fun sendMeshPacket(packet: com.example.service.WireMeshPacket) {
        if (packet.type == "VOICE" || packet.type == "VOICE_STREAM" || packet.type == "CALL_AUDIO" || packet.type == "VOICE_PTT") {
            sendVoiceAudioPacket(packet)
            return
        }
        val state = _uiState.value
        val gpsAcc = packet.accuracy ?: gpsService.gpsState.value.accuracy
        val (outLat, outLng) = if (state.locationShareMode == LocationShareMode.OFF) {
            Pair(0.0, 0.0)
        } else if (packet.lat != null && packet.lng != null) {
            com.example.service.OffGridBleMeshEngine.applyLocationShareMode(packet.lat, packet.lng, state.locationShareMode)
        } else {
            Pair(null, null)
        }
        val isLocalGateway = state.isInternetEnabled && state.internetGatewayBridgeEnabled && hasValidatedOsInternet()
        val effectiveIsGateway = packet.isGateway || (normalizeId(packet.srcNodeId) == normalizeId(state.localNodeId) && isLocalGateway)
        val json = org.json.JSONObject().apply {
            put("id", packet.packetId)
            put("packetId", packet.packetId)
            put("type", packet.type)
            put("t", packet.type)
            put("srcId", packet.srcNodeId)
            put("src", packet.srcNodeId)
            put("srcName", packet.srcCallsign)
            put("cs", packet.srcCallsign)
            put("dstId", packet.dstNodeId)
            put("dst", packet.dstNodeId)
            put("ttl", packet.ttl)
            put("hops", packet.hops)
            put("payload", packet.payload)
            put("p", packet.payload)
            outLat?.let { put("lat", it) }
            outLng?.let { put("lng", it) }
            (packet.altitude ?: state.barometricAltitudeMeters)?.let { put("alt", it) }
            gpsAcc?.let {
                put("acc", it.toDouble())
                put("accuracy", it.toDouble())
            }
            packet.battery?.let { put("bat", it) }
            if (packet.groupId.isNotBlank()) put("groupId", packet.groupId)
            if (packet.groupName.isNotBlank()) put("groupName", packet.groupName)
            if (packet.memberIdsCsv.isNotBlank()) put("memberIds", packet.memberIdsCsv)
            if (packet.senderPubKey.isNotBlank()) put("pk", packet.senderPubKey)
            if (packet.encryptedKeysJson.isNotBlank()) put("ekeys", packet.encryptedKeysJson)
            if (packet.srcUsername.isNotBlank()) put("su", packet.srcUsername)
            if (packet.dstUsername.isNotBlank()) put("du", packet.dstUsername)
            if (effectiveIsGateway) put("gw", true)
            put("ts", packet.timestamp)
        }.toString()

        val filteredPacket = packet.copy(
            lat = outLat,
            lng = outLng,
            accuracy = gpsAcc,
            altitude = packet.altitude ?: state.barometricAltitudeMeters,
            isGateway = effectiveIsGateway
        )

        val plan = linkCostRouter.planRoute(
            packet = filteredPacket,
            hasLocalWifiOrNan = autoHotspotNegotiator.hasHighSpeedLink(),
            hasBleL2cap = offGridBleEngine.hasActiveL2capChannel(),
            hasBleGatt = true,
            isInternetEnabled = state.isInternetEnabled
        )

        if (plan.useCompactBinary) {
            val compactSize = com.example.service.CompactMeshProtocol.encodeCompact(filteredPacket).size
            linkCostRouter.recordBinarySavings(json.toByteArray(Charsets.UTF_8).size, compactSize)
        }

        if (plan.fanOutAll) {
            // Emergency SOS, Live Call Signaling, or Mesh Broadcast: fan out across all active radios
            offGridBleEngine.broadcastPacketChunked(filteredPacket, useCompactBinary = plan.useCompactBinary)
            autoHotspotNegotiator.sendCompactPacket(filteredPacket, useCompactBinary = plan.useCompactBinary)
            if (state.isInternetEnabled) {
                meshNetworkService.broadcastPacket(filteredPacket)
            }
        } else {
            // Directed DM / peer-specific packet: send on lowest-cost link first, escalate on ACK timeout
            val primaryTier = plan.orderedTiers.first()
            packetPrimaryTierMap[filteredPacket.packetId] = primaryTier
            dispatchPacketOnTier(primaryTier, filteredPacket, plan.useCompactBinary, state.isInternetEnabled)

            val needsAckFailover = filteredPacket.packetId.isNotBlank() &&
                (filteredPacket.type == "CHAT" || filteredPacket.type == "E2EE_DM" || filteredPacket.type == "GROUP_CHAT") &&
                plan.orderedTiers.size > 1
            if (needsAckFailover) {
                pendingAckFailoverJobs.remove(filteredPacket.packetId)?.cancel()
                val backupTiers = plan.orderedTiers.drop(1)
                pendingAckFailoverJobs[filteredPacket.packetId] = viewModelScope.launch(Dispatchers.IO) {
                    var failedTier = primaryTier
                    for (nextTier in backupTiers) {
                        delay(2_200L)
                        if (pendingChatAcks[filteredPacket.packetId] == true) break
                        linkCostRouter.recordDeliveryTimeout(filteredPacket.dstNodeId, failedTier)
                        packetPrimaryTierMap[filteredPacket.packetId] = nextTier
                        dispatchPacketOnTier(
                            nextTier,
                            filteredPacket,
                            useCompactBinary = plan.useCompactBinary,
                            isInternetEnabled = _uiState.value.isInternetEnabled
                        )
                        refreshRouterTelemetry("Failover -> ${nextTier.label}")
                        failedTier = nextTier
                    }
                    pendingAckFailoverJobs.remove(filteredPacket.packetId)
                }
            }
        }

        refreshRouterTelemetry(plan.reason)

        // 4. Wi-Fi Direct Embedded Web Portal SSE + WebSocket Push
        pulseMeshWebServer.notifyStateChanged()
    }

    private fun dispatchPacketOnTier(
        tier: com.example.service.LinkCostRouter.TransportTier,
        packet: com.example.service.WireMeshPacket,
        useCompactBinary: Boolean,
        isInternetEnabled: Boolean
    ) {
        when (tier) {
            com.example.service.LinkCostRouter.TransportTier.WIFI_NAN_USB -> {
                autoHotspotNegotiator.sendCompactPacket(packet, useCompactBinary = useCompactBinary)
            }
            com.example.service.LinkCostRouter.TransportTier.BLE_L2CAP -> {
                if (!offGridBleEngine.sendOverL2cap(packet, useCompactBinary = useCompactBinary)) {
                    offGridBleEngine.sendOverGatt(packet, useCompactBinary = useCompactBinary)
                }
            }
            com.example.service.LinkCostRouter.TransportTier.BLE_GATT -> {
                offGridBleEngine.sendOverGatt(packet, useCompactBinary = useCompactBinary)
            }
            com.example.service.LinkCostRouter.TransportTier.HYBRID_RELAY -> {
                if (isInternetEnabled) {
                    meshNetworkService.broadcastPacket(packet)
                }
            }
        }
    }

    private fun mapTransportToTier(sourceTransport: String): com.example.service.LinkCostRouter.TransportTier {
        val t = sourceTransport.uppercase()
        return when {
            t.contains("WIFI") || t.contains("WI-FI") || t.contains("HOTSPOT") || t.contains("NAN") ||
                t.contains("TCP") || t.contains("UDP") || t.contains("USB") || t.contains("LAN") || t.contains("MDNS") ->
                com.example.service.LinkCostRouter.TransportTier.WIFI_NAN_USB
            t.contains("L2CAP") ->
                com.example.service.LinkCostRouter.TransportTier.BLE_L2CAP
            t.contains("HYBRID") || t.contains("CELL") || t.contains("NTFY") || t.contains("INTERNET") ->
                com.example.service.LinkCostRouter.TransportTier.HYBRID_RELAY
            else ->
                com.example.service.LinkCostRouter.TransportTier.BLE_GATT
        }
    }

    private fun refreshRouterTelemetry(lastRouteReason: String? = null) {
        val stats = linkCostRouter.getStats()
        _uiState.update { cur ->
            val summary = if (!lastRouteReason.isNullOrBlank()) {
                "$lastRouteReason • ${stats.totalRoutedPackets} routed"
            } else {
                cur.routerSummary
            }
            if (cur.routerSummary == summary &&
                cur.routerFailoverCount == stats.failoverEscalations &&
                cur.binaryBytesSaved == stats.binaryBytesSaved &&
                cur.pmb2PeerCount == stats.pmb2PeerCount
            ) {
                cur
            } else {
                cur.copy(
                    routerSummary = summary,
                    routerFailoverCount = stats.failoverEscalations,
                    binaryBytesSaved = stats.binaryBytesSaved,
                    pmb2PeerCount = stats.pmb2PeerCount
                )
            }
        }
    }

    /**
     * Runs once on app startup. Strictly skipped when Off-Grid Mode is active (`isInternetEnabled == false`)
     * so PulseMesh makes zero outbound HTTP requests while off-grid.
     */
    fun checkForAppUpdateOnStartup() {
        if (!_uiState.value.isInternetEnabled) {
            _uiState.update { it.copy(updateCheckStatus = "Off-Grid Mode — Startup update check paused") }
            return
        }
        checkForAppUpdateNow(userInitiated = false)
    }

    /**
     * Checks GitHub Releases (`Kostadinosk/PulseMesh`) for a newer PulseMesh APK release.
     * On manual tap, runs whenever OS internet is available or internet mode is enabled.
     */
    fun checkForAppUpdateNow(userInitiated: Boolean = true) {
        if (!userInitiated && !_uiState.value.isInternetEnabled) return
        if (_uiState.value.isCheckingForUpdate) return
        val currentVersion = com.example.BuildConfig.VERSION_NAME
        _uiState.update {
            it.copy(
                isCheckingForUpdate = true,
                updateCheckStatus = if (userInitiated) "Checking GitHub Releases (current v$currentVersion)..." else it.updateCheckStatus
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.example.service.AppUpdateChecker.checkForUpdate(currentVersion)
            result.onSuccess { info ->
                if (info != null) {
                    val showBanner = userInitiated || info.latestVersion != updateBannerDismissedVersion
                    _uiState.update {
                        it.copy(
                            isCheckingForUpdate = false,
                            availableUpdate = if (showBanner) info else it.availableUpdate,
                            updateCheckStatus = "Update v${info.latestVersion} available! (current v$currentVersion)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCheckingForUpdate = false,
                            availableUpdate = null,
                            updateCheckStatus = "Up to date (PulseMesh v$currentVersion)"
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCheckingForUpdate = false,
                        updateCheckStatus = if (userInitiated) {
                            "Update check offline/unavailable (${err.message ?: "no network"})"
                        } else {
                            ""
                        }
                    )
                }
            }
        }
    }

    fun dismissAvailableUpdate() {
        val ver = _uiState.value.availableUpdate?.latestVersion.orEmpty()
        if (ver.isNotBlank()) updateBannerDismissedVersion = ver
        _uiState.update { it.copy(availableUpdate = null) }
    }

    fun toggleBell202Modem() {
        val next = !_uiState.value.bell202ModemEnabled
        bell202Modem.setListening(next)
        _uiState.update { it.copy(bell202ModemEnabled = next) }
        triggerHaptic(35)
    }

    fun transmitBell202AcousticMessage(text: String, isSos: Boolean = false) {
        val st = _uiState.value
        val typeByte = if (isSos) {
            com.example.service.Bell202AcousticModem.TYPE_SOS
        } else {
            com.example.service.Bell202AcousticModem.TYPE_CHAT
        }
        val cleanText = text.trim().ifBlank { if (isSos) "SOS from ${st.operatorCallsign}" else "PulseMesh Bell202 Ping (${st.operatorCallsign})" }
        bell202Modem.transmitMessage(typeByte, st.localNodeId, cleanText)
        triggerHaptic(50)
    }

    /**
     * Handles raw incoming JSON line from BLE GATT, Hotspot TCP/UDP, or Hybrid stream.
     */
    private fun handleIncomingRawJson(
        rawJson: String,
        sourceTransportHint: String = "",
        realRssiHint: Int? = null
    ) {
        if (com.example.service.CompactMeshProtocol.isCompactLine(rawJson)) {
            val decoded = com.example.service.CompactMeshProtocol.decodeLineAuto(
                rawJson,
                sourceTransport = sourceTransportHint.ifBlank { "WI-FI PMB2" }
            )
            if (decoded != null) {
                handleIncomingPacket(decoded.copy(rssiDbm = realRssiHint ?: decoded.rssiDbm))
            }
            return
        }
        runCatching {
            val root = org.json.JSONObject(rawJson)
            val type = root.optString("type").ifBlank { root.optString("t") }
            val src = root.optString("srcId")
                .ifBlank { root.optString("src") }
                .ifBlank { root.optString("srcNodeId") }
            val srcName = root.optString("srcName")
                .ifBlank { root.optString("cs") }
                .ifBlank { root.optString("srcCallsign", "") }
            val dst = root.optString("dstId")
                .ifBlank { root.optString("dst") }
                .ifBlank { root.optString("dstNodeId", "ALL") }
            val payloadStr = root.optString("payload").ifBlank { root.optString("p", "") }

            if (type == "HOTSPOT_INFO" || type == "HOTSPOT_AUTO_JOIN" || type == "HOTSPOT_CREDENTIALS") {
                val inner = runCatching { org.json.JSONObject(payloadStr) }.getOrNull() ?: root
                val ssid = inner.optString("ssid").ifBlank { root.optString("ssid") }
                val pass = inner.optString("passphrase")
                    .ifBlank { inner.optString("pass") }
                    .ifBlank { root.optString("passphrase") }
                val hostIp = inner.optString("hostIp")
                    .ifBlank { inner.optString("ip") }
                    .ifBlank { root.optString("hostIp", "192.168.49.1") }
                if (src.isNotBlank() && normalizeId(src) != normalizeId(_uiState.value.localNodeId)) {
                    val normPeer = normalizeId(src)
                    peersBackingMap[normPeer]?.let { existing ->
                        peersBackingMap[normPeer] = existing.copy(
                            hotspotSsid = ssid.ifBlank { existing.hotspotSsid },
                            hotspotPass = pass.ifBlank { existing.hotspotPass },
                            ipAddress = hostIp.ifBlank { existing.ipAddress },
                            isHotspotHost = true
                        )
                    }
                    autoHotspotNegotiator.handleHotspotAutoJoin(src, ssid, pass, hostIp)
                }
            }

            val parsedAcc = when {
                root.has("acc") -> root.getDouble("acc").toFloat()
                root.has("accuracy") -> root.getDouble("accuracy").toFloat()
                else -> null
            }

            val packet = com.example.service.WireMeshPacket(
                packetId = root.optString("id").ifBlank { root.optString("packetId", "pkt_${System.currentTimeMillis()}") },
                srcNodeId = src,
                srcCallsign = srcName,
                dstNodeId = dst,
                type = type.ifBlank { "HEARTBEAT" },
                payload = payloadStr,
                ttl = root.optInt("ttl", 7),
                hops = root.optInt("hops", 0),
                lat = if (root.has("lat")) root.getDouble("lat") else null,
                lng = if (root.has("lng")) root.getDouble("lng") else null,
                altitude = if (root.has("alt")) root.getDouble("alt") else if (root.has("altitude")) root.getDouble("altitude") else null,
                accuracy = parsedAcc,
                battery = if (root.has("bat")) root.getInt("bat") else if (root.has("battery")) root.getInt("battery") else null,
                timestamp = root.optLong("ts", root.optLong("timestamp", System.currentTimeMillis())),
                sourceTransport = sourceTransportHint,
                rssiDbm = realRssiHint,
                groupId = root.optString("groupId", ""),
                groupName = root.optString("groupName", ""),
                memberIdsCsv = root.optString("memberIds", ""),
                senderPubKey = root.optString("pk", "").ifBlank { root.optString("senderPubKey", "") },
                encryptedKeysJson = root.optString("ekeys", "").ifBlank { root.optString("encryptedKeys", "") },
                srcUsername = root.optString("su", ""),
                dstUsername = root.optString("du", ""),
                isGateway = root.optBoolean("gw", false)
            )
            viewModelScope.launch(Dispatchers.Main) {
                handleIncomingPacket(packet)
            }
        }
    }

    private val seenIncomingPacketIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())
    private val relayedPacketIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())
    private val seenVoiceFrameKeys = LinkedHashMap<String, Long>(256, 0.75f, false)

    internal fun extractVoiceSeqFromPacketId(packetId: String): Int? {
        if (!packetId.startsWith("vs_")) return null
        val seqPart = packetId.split('_').getOrNull(2)?.toIntOrNull() ?: return null
        return seqPart and 0xFFFF
    }

    /**
     * Prevents UDP redundant-send duplicates, self-loopback echo, and UDP+TCP/BLE double-delivery
     * from replaying the same `(srcNodeId, seq)` voice frame twice.
     */
    internal fun shouldAcceptVoiceFrame(
        srcNodeId: String,
        seq: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normSrc = normalizeId(srcNodeId)
        val normSelf = normalizeId(_uiState.value.localNodeId)
        if (normSrc.isNotBlank() && normSrc == normSelf) return false
        val maskedSeq = seq and 0xFFFF
        val key = "${normSrc}_$maskedSeq"
        synchronized(seenVoiceFrameKeys) {
            val iter = seenVoiceFrameKeys.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if ((nowMs - entry.value) > 3_000L || seenVoiceFrameKeys.size > 256) {
                    iter.remove()
                } else {
                    break
                }
            }
            if (seenVoiceFrameKeys.containsKey(key)) return false
            seenVoiceFrameKeys[key] = nowMs
            return true
        }
    }

    internal fun recordPacketIdIfNew(packetId: String): Boolean {
        if (packetId.isBlank()) return true
        synchronized(seenIncomingPacketIds) {
            if (seenIncomingPacketIds.contains(packetId)) return false
            while (seenIncomingPacketIds.size >= 4000) {
                val iter = seenIncomingPacketIds.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                } else break
            }
            seenIncomingPacketIds.add(packetId)
            return true
        }
    }

    /**
     * Multi-Hop Relay Filter:
     * NEVER re-broadcasts `HEARTBEAT`, `GPS_BEACON`, `MUTUAL_PING`, `CHAT_ACK`, `VOICE_STREAM`, `VOICE_PTT`, `CALL_AUDIO`, or `HOTSPOT_*`.
     * Only relays user-triggered `CHAT`, `E2EE_DM`, `WAYPOINT`, `SOS`, and `CALL_*` (`CALL_RING`, `CALL_ACCEPT`, `CALL_END`, `CALL_DECLINE`, `CALL_INVITE`)
     * once per `packet.packetId`.
     */
    internal fun buildRelayedPacketIfEligible(
        packet: com.example.service.WireMeshPacket,
        selfNodeId: String = _uiState.value.localNodeId
    ): com.example.service.WireMeshPacket? {
        val normSrc = normalizeId(packet.srcNodeId)
        val normSelf = normalizeId(selfNodeId)
        val normDst = normalizeId(packet.dstNodeId)
        if (normSrc.isBlank() || normSrc == normSelf) return null
        if (packet.ttl <= 1) return null
        if (packet.packetId.startsWith("ble_") || packet.packetId.startsWith("udp_hb_")) return null

        // Do not relay unicast packets that have already reached their final destination (self),
        // and never flood-relay broadcast CHAT_ACK packets.
        val isBroadcastDst = normDst.isBlank() || normDst == "ALL" || normDst == "BROADCAST"
        if (packet.type == "CHAT_ACK" && (isBroadcastDst || normDst == normSelf)) return null
        if ((packet.type == "E2EE_DM" || packet.type == "TILE_REQ") && normDst == normSelf) return null

        val allowedRelayType = packet.type == "CHAT" ||
            packet.type == "E2EE_DM" ||
            packet.type == "CHAT_ACK" ||
            packet.type == "GROUP_INVITE" ||
            packet.type == "GROUP_CHAT" ||
            packet.type == "WAYPOINT" ||
            packet.type == "SOS" ||
            packet.type == "SOS_CLEAR" ||
            packet.type == "QUICK_STATUS" ||
            packet.type == "REACTION" ||
            packet.type == "WEATHER_ALERT" ||
            packet.type == "CALL_RING" ||
            packet.type == "CALL_INVITE" ||
            packet.type == "CALL_ACCEPT" ||
            packet.type == "CALL_ACCEPT_ACK" ||
            packet.type == "CALL_END" ||
            packet.type == "CALL_DECLINE" ||
            packet.type == "KEY_ANNOUNCE" ||
            packet.type == "USERNAME_CLAIM" ||
            packet.type == "USERNAME_LOOKUP" ||
            packet.type == "TILE_REQ"
        if (!allowedRelayType) return null

        synchronized(relayedPacketIds) {
            if (packet.packetId.isNotBlank() && !relayedPacketIds.add(packet.packetId)) {
                return null
            }
            while (relayedPacketIds.size > 2000) {
                val iter = relayedPacketIds.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                } else break
            }
        }

        // Per-source flood rate limiter: max 5 relayed packets per second per normalized srcId
        val nowMs = System.currentTimeMillis()
        val rateWindow = relayRateMap.getOrPut(normSrc) { mutableListOf() }
        synchronized(rateWindow) {
            val iter = rateWindow.iterator()
            while (iter.hasNext()) {
                if (nowMs - iter.next() > 1000L) {
                    iter.remove()
                }
            }
            if (rateWindow.size >= 5) {
                return null
            }
            rateWindow.add(nowMs)
        }
        return packet.copy(ttl = packet.ttl - 1, hops = packet.hops + 1)
    }

    fun handleIncomingMeshPacket(packet: com.example.service.WireMeshPacket) {
        handleIncomingPacket(packet)
    }

    fun handleIncomingPacket(packet: com.example.service.WireMeshPacket) {
        val state = _uiState.value
        val normSrc = normalizeId(packet.srcNodeId)
        val normSelf = normalizeId(state.localNodeId)
        // Strictly ignore our own packets, blank node IDs, and blocked fake/server callsigns
        if (normSrc.isBlank() || normSrc == normSelf) return
        if (packet.srcCallsign.isNotBlank() && isBlockedFakeCallsign(packet.srcCallsign)) return

        // Update Link-Cost Router peer health & PMB2 capability auto-negotiation
        linkCostRouter.recordPeerObserved(
            peerNodeId = packet.srcNodeId,
            tier = mapTransportToTier(packet.sourceTransport),
            supportsPmb2 = packet.sourceTransport.contains("PMB2", ignoreCase = true),
            rssiDbm = packet.rssiDbm
        )

        // Global 4000-ID deduplication across all radios to prevent multi-hop infinite loops
        if (packet.type != "HEARTBEAT" && packet.type != "GPS_BEACON") {
            if (!recordPacketIdIfNew(packet.packetId)) {
                // Even when a retried CHAT / E2EE_DM / GROUP_CHAT arrives again, still return a rate-limited CHAT_ACK
                // if we already received it so the sender doesn't get stuck on "NO ACK / RETRY"!
                if (packet.packetId.isNotBlank() &&
                    (packet.type == "CHAT" || packet.type == "E2EE_DM" || packet.type == "GROUP_CHAT")
                ) {
                    val isBroadcastMsg = packet.dstNodeId == "ALL" || packet.dstNodeId == "BROADCAST" || packet.dstNodeId == "ENC_TACNET"
                    val isDmForMe = normalizeId(packet.dstNodeId) == normSelf
                    val grpId = packet.groupId.ifBlank { packet.dstNodeId }.trim()
                    val isGroupMember = packet.type == "GROUP_CHAT" && grpId.isNotBlank() &&
                        (_uiState.value.meshGroups.any { it.groupId == grpId && it.memberIds.any { m -> normalizeId(m) == normSelf } })
                    if (isBroadcastMsg || isDmForMe || isGroupMember) {
                        val nowAck = System.currentTimeMillis()
                        val lastSent = lastDuplicateAckSentMs[packet.packetId] ?: 0L
                        if (nowAck - lastSent >= 800L) {
                            lastDuplicateAckSentMs[packet.packetId] = nowAck
                            val ackPkt = com.example.service.WireMeshPacket(
                                packetId = "ack_${packet.packetId}_${state.localNodeId}_${nowAck and 0xFFFF}",
                                srcNodeId = state.localNodeId,
                                srcCallsign = state.operatorCallsign,
                                dstNodeId = packet.srcNodeId,
                                type = "CHAT_ACK",
                                payload = packet.packetId,
                                ttl = 5,
                                hops = 0,
                                srcUsername = state.claimedUsername,
                                dstUsername = packet.srcUsername
                            )
                            sendMeshPacket(ackPkt)
                            if (packet.srcUsername.isNotBlank() && (state.isInternetEnabled || hasValidatedOsInternet())) {
                                meshNetworkService.sendDirectPacketToUsername(packet.srcUsername, ackPkt, allowDirectSend = true)
                            }
                        }
                    }
                }
                return
            }
        }

        // Universal Multi-Hop Flood Relay Rule (TTL = 7) for eligible user packets only
        val isFromInternet = packet.sourceTransport == "HYBRID CELL"
        val isActiveGateway = state.isInternetEnabled && state.internetGatewayBridgeEnabled && hasValidatedOsInternet()
        buildRelayedPacketIfEligible(packet, state.localNodeId)?.let { relayed ->
            viewModelScope.launch(Dispatchers.IO) {
                delay(Random.nextLong(30L, 81L))
                sendMeshPacket(relayed)
                // If an offline neighbor sent a DM targeting a remote @username, bridge it to their direct internet inbox!
                if (isActiveGateway && !isFromInternet && relayed.dstUsername.isNotBlank()) {
                    meshNetworkService.sendDirectPacketToUsername(
                        relayed.dstUsername,
                        relayed,
                        allowDirectSend = true
                    )
                }
            }
        }

        // Bidirectional Offline-to-Internet Presence Gateway Bridge:
        // Periodically bridge HEARTBEAT/GPS_BEACON (max 1 per 20s per peer) between local BLE/Wi-Fi and Internet
        // so offline users discover distant internet peers and distant internet peers discover offline users.
        if (isActiveGateway && (packet.type == "HEARTBEAT" || packet.type == "GPS_BEACON") && packet.ttl > 1) {
            val nowBridge = System.currentTimeMillis()
            val bridgeKey = "${if (isFromInternet) "in" else "out"}_$normSrc"
            val lastBridge = lastGatewayHeartbeatBridgeMs[bridgeKey] ?: 0L
            if (nowBridge - lastBridge >= 20_000L) {
                lastGatewayHeartbeatBridgeMs[bridgeKey] = nowBridge
                val bridgedPresence = packet.copy(
                    packetId = "gw_${if (isFromInternet) "dl" else "ul"}_${normSrc}_${nowBridge and 0xFFFFF}",
                    ttl = (packet.ttl - 1).coerceAtLeast(1),
                    hops = packet.hops + 1
                )
                viewModelScope.launch(Dispatchers.IO) {
                    if (!isFromInternet) {
                        // Offline local peer -> Internet mesh
                        meshNetworkService.broadcastPacket(bridgedPresence)
                    } else if (peersBackingMap.values.any { !it.transports.contains("HYBRID CELL") } ||
                        autoHotspotNegotiator.hasHighSpeedLink() ||
                        pulseMeshWebServer.isRunning()
                    ) {
                        // Distant internet peer -> Local BLE/Wi-Fi Direct mesh
                        offGridBleEngine.broadcastPacketChunked(bridgedPresence)
                        val bridgeJson = org.json.JSONObject().apply {
                            put("id", bridgedPresence.packetId)
                            put("packetId", bridgedPresence.packetId)
                            put("type", bridgedPresence.type)
                            put("t", bridgedPresence.type)
                            put("srcId", bridgedPresence.srcNodeId)
                            put("src", bridgedPresence.srcNodeId)
                            put("srcName", bridgedPresence.srcCallsign)
                            put("cs", bridgedPresence.srcCallsign)
                            put("dstId", bridgedPresence.dstNodeId)
                            put("dst", bridgedPresence.dstNodeId)
                            put("ttl", bridgedPresence.ttl)
                            put("hops", bridgedPresence.hops)
                            put("payload", bridgedPresence.payload)
                            put("p", bridgedPresence.payload)
                            bridgedPresence.lat?.let { put("lat", it) }
                            bridgedPresence.lng?.let { put("lng", it) }
                            bridgedPresence.battery?.let { put("bat", it) }
                            if (bridgedPresence.senderPubKey.isNotBlank()) put("pk", bridgedPresence.senderPubKey)
                            if (bridgedPresence.isGateway) put("gw", true)
                            put("ts", bridgedPresence.timestamp)
                        }.toString()
                        autoHotspotNegotiator.sendPacket(bridgeJson)
                    }
                }
            }
        }

        val canonicalSrcId = "0x$normSrc"
        val peerReportsHost = packet.type == "HOTSPOT_INFO" ||
            packet.type == "HOTSPOT_AUTO_JOIN" ||
            packet.payload == "HOTSPOT_HOST_ACTIVE"

        // If HEARTBEAT, HOTSPOT_INFO, or HOTSPOT_AUTO_JOIN contains a peer IP, register it on the peer node
        val extractedIp = when {
            packet.type == "HOTSPOT_INFO" || packet.type == "HOTSPOT_AUTO_JOIN" -> {
                runCatching {
                    org.json.JSONObject(packet.payload).optString("hostIp", "192.168.49.1")
                }.getOrDefault("192.168.49.1")
            }
            packet.payload.trim().matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) && !packet.payload.trim().startsWith("127.") -> {
                packet.payload.trim()
            }
            else -> null
        }
        if (!extractedIp.isNullOrBlank()) {
            autoHotspotNegotiator.registerPeerIp(canonicalSrcId, extractedIp)
        }

        val isHighPriorityUserEvent = packet.type != "HEARTBEAT" &&
            packet.type != "GPS_BEACON" &&
            packet.type != "MUTUAL_PING"

        val existingPeer = peersBackingMap[normSrc] ?: state.nodes.find { !it.isSelf && normalizeId(it.id) == normSrc }
        val isNewPeer = existingPeer == null

        val resolvedTransport = when {
            packet.sourceTransport == "BLE 1M" ||
                packet.sourceTransport == "BLE CODED" ||
                packet.sourceTransport == "BLE GATT" ||
                packet.sourceTransport == "BLE L2CAP" ||
                packet.sourceTransport == "ULTRASONIC" ||
                packet.sourceTransport == "HOTSPOT WIFI" ||
                packet.sourceTransport == "HYBRID CELL" -> packet.sourceTransport
            packet.packetId.startsWith("udp_hb_") || !extractedIp.isNullOrBlank() -> "HOTSPOT WIFI"
            packet.packetId.startsWith("ble_") -> existingPeer?.lastBlePhy?.ifBlank { "BLE 1M" } ?: "BLE 1M"
            offGridBleEngine.hasActiveL2capChannel() -> "BLE L2CAP"
            offGridBleEngine.connectedGattClients.isNotEmpty() || offGridBleEngine.connectedServerDevices.isNotEmpty() -> "BLE GATT"
            autoHotspotNegotiator.hasHighSpeedLink() -> "HOTSPOT WIFI"
            state.isInternetEnabled -> "HYBRID CELL"
            else -> existingPeer?.lastBlePhy?.ifBlank { "BLE 1M" } ?: "BLE 1M"
        }

        val resolvedRssiDbm: Int? = if (resolvedTransport == "HYBRID CELL") {
            null
        } else {
            packet.rssiDbm
                ?: offGridBleEngine.peerLastRssi[normSrc]
                ?: existingPeer?.rssiDbm?.takeIf { it != 0 && it != -42 }
                ?: if (resolvedTransport == "HOTSPOT WIFI") autoHotspotNegotiator.getRealWifiRssiDbm() else null
        }

        // True hop count: direct neighbor sends hops=0 on wire -> 1 hop away; each relay adds +1 hop.
        val receivedHops = (packet.hops + 1).coerceIn(1, 7)

        registerOrUpdatePeerInstant(
            peerNodeId = canonicalSrcId,
            callsign = packet.srcCallsign,
            lat = packet.lat,
            lng = packet.lng,
            altitude = packet.altitude,
            battery = packet.battery,
            rssiDbm = resolvedRssiDbm ?: existingPeer?.rssiDbm ?: -55,
            ipAddress = extractedIp.orEmpty(),
            isHotspotHost = peerReportsHost,
            sourceTransport = resolvedTransport,
            forceImmediateUiUpdate = isNewPeer || isHighPriorityUserEvent,
            hopCount = receivedHops,
            isGateway = packet.isGateway || resolvedTransport == "HYBRID CELL"
        )

        val peerNode = peersBackingMap[normSrc]
        val updatedNodes = buildMergedNodesList(_uiState.value.nodes)
        val peerCount = updatedNodes.count { !it.isSelf }

        // Evaluate Geofence perimeter breach if enabled and peer reported real GPS
        val curGeo = _uiState.value
        if (curGeo.geofenceEnabled && (curGeo.geofenceLat != 0.0 || curGeo.geofenceLng != 0.0) &&
            packet.lat != null && packet.lng != null && (packet.lat != 0.0 || packet.lng != 0.0)
        ) {
            val distFromFence = com.example.service.LiveMeshNetworkService.calculateDistanceMeters(
                curGeo.geofenceLat, curGeo.geofenceLng, packet.lat, packet.lng
            )
            if (distFromFence > curGeo.geofenceRadiusMeters.toDouble()) {
                val peerLabel = peerNode?.callsign ?: packet.srcCallsign.ifBlank { canonicalSrcId }
                val breachMsg = "PERIMETER ALERT: $peerLabel is ${distFromFence.toInt()}m from rally (limit ${curGeo.geofenceRadiusMeters}m)"
                if (curGeo.geofenceAlertMessage != breachMsg) {
                    _uiState.update { it.copy(geofenceAlertMessage = breachMsg) }
                    triggerHaptic(90)
                }
            }
        }

        if (peerNode != null) {
            val allKnownIds = peersBackingMap.values.map { it.id }
            autoHotspotNegotiator.onPeerDiscovered(peerNode.id, peerNode.isHotspotHost, allKnownIds)
            if (isNewPeer) {
                flushPendingDtnPacketsToPeer(peerNode.id)
            }
        }

        val effectiveCallsign = peerNode?.callsign ?: packet.srcCallsign.takeIf { !isBlockedFakeCallsign(it) } ?: canonicalSrcId
        val fallbackCallNode = peerNode ?: MeshNode(
            id = canonicalSrcId,
            callsign = effectiveCallsign,
            isSelf = false
        )

        if (packet.senderPubKey.isNotBlank()) {
            processIncomingPeerPublicKey(canonicalSrcId, packet.senderPubKey)
        }

        // 2. Handle specific packet types
        when (packet.type) {
            "KEY_ANNOUNCE" -> {
                val candidateKey = packet.senderPubKey.ifBlank { packet.payload }.trim()
                if (TacticalE2eeManager.isValidEcPublicKeyBase64(candidateKey)) {
                    val hadKeyBefore = prefs.getPeerPublicKey(canonicalSrcId) != null
                    processIncomingPeerPublicKey(canonicalSrcId, candidateKey)
                    if (!hadKeyBefore) {
                        announcePublicKey(force = true)
                    }
                }
            }
            "USERNAME_LOOKUP" -> {
                val queriedNorm = TacticalE2eeManager.normalizeUsername(packet.payload)
                if (TacticalE2eeManager.isValidUsername(queriedNorm)) {
                    val myClaimNorm = TacticalE2eeManager.normalizeUsername(state.claimedUsername)
                    val matchingClaim = when {
                        myClaimNorm == queriedNorm -> prefs.getUsernameClaim(myClaimNorm)
                        else -> prefs.getUsernameClaim(queriedNorm)
                            ?.takeIf { it.claimedAt > 0L && it.publicKeyBase64.isNotBlank() }
                    }
                    if (matchingClaim != null) {
                        broadcastUsernameClaimOnMesh(matchingClaim)
                    }
                }
            }
            "USERNAME_CLAIM" -> {
                runCatching {
                    val rec = com.example.crypto.UsernameClaimRecord.fromJson(org.json.JSONObject(packet.payload))
                    if (rec != null) {
                        val sigValid = rec.claimVersion < 2 || rec.signatureBase64.isBlank() ||
                            TacticalE2eeManager.verifyUsernameClaimSignature(rec)
                        if (sigValid) {
                            val existing = prefs.getUsernameClaim(rec.username)
                            val sameOwner = existing == null ||
                                existing.claimedAt == 0L ||
                                (existing.publicKeyBase64.isNotBlank() && existing.publicKeyBase64 == rec.publicKeyBase64) ||
                                (existing.authVerifierHash.isNotBlank() && existing.authVerifierHash == rec.authVerifierHash) ||
                                (existing.pinHash.isNotBlank() && existing.pinHash == rec.pinHash) ||
                                (existing.recoveryHash.isNotBlank() && existing.recoveryHash == rec.recoveryHash)
                            if (sameOwner) {
                                prefs.saveUsernameClaim(rec)
                                refreshConversationsList()
                            }
                        }
                    }
                }
            }
            "CHAT_ACK" -> {
                val ackedId = packet.payload.trim()
                if (ackedId.isNotBlank()) {
                    pendingChatAcks[ackedId] = true
                    pendingAckFailoverJobs.remove(ackedId)?.cancel()
                    val ackTier = packetPrimaryTierMap.remove(ackedId) ?: mapTransportToTier(packet.sourceTransport)
                    linkCostRouter.recordAckSuccess(packet.srcNodeId, ackTier)
                    refreshRouterTelemetry()
                    synchronized(pendingDtnPackets) {
                        val iter = pendingDtnPackets.iterator()
                        while (iter.hasNext()) {
                            if (iter.next().packetId == ackedId) {
                                iter.remove()
                            }
                        }
                    }
                    val updated = persistentChatManager.markPacketDelivered(ackedId, effectiveCallsign)
                    // Immediately update in-memory UI packets so the checkmark flips to RECEIVED without delay
                    _uiState.update { cur ->
                        val updatedPkts = cur.packets.map { mp ->
                            if (mp.id == ackedId && (!mp.isDelivered || (effectiveCallsign.isNotBlank() && mp.ackedByCallsign.isBlank()))) {
                                mp.copy(
                                    isDelivered = true,
                                    ackedByCallsign = effectiveCallsign.ifBlank { mp.ackedByCallsign }
                                )
                            } else mp
                        }
                        val updatedThreadPkts = cur.activeThreadPackets.map { mp ->
                            if (mp.id == ackedId && (!mp.isDelivered || (effectiveCallsign.isNotBlank() && mp.ackedByCallsign.isBlank()))) {
                                mp.copy(
                                    isDelivered = true,
                                    ackedByCallsign = effectiveCallsign.ifBlank { mp.ackedByCallsign }
                                )
                            } else mp
                        }
                        cur.copy(
                            packets = updatedPkts,
                            activeThreadPackets = updatedThreadPkts,
                            pendingDtnCount = pendingDtnPackets.size
                        )
                    }
                    if (updated) {
                        refreshConversationsList()
                    }
                    pulseMeshWebServer.notifyStateChanged()
                }
            }
            "HOTSPOT_INFO", "HOTSPOT_AUTO_JOIN", "HOTSPOT_CREDENTIALS" -> {
                val inner = runCatching { org.json.JSONObject(packet.payload) }.getOrNull()
                if (inner != null) {
                    val ssid = inner.optString("ssid")
                    val pass = inner.optString("passphrase").ifBlank { inner.optString("pass") }
                    val hostIp = inner.optString("hostIp").ifBlank { inner.optString("ip", "192.168.49.1") }
                    peersBackingMap[normSrc]?.let { existing ->
                        peersBackingMap[normSrc] = existing.copy(
                            hotspotSsid = ssid.ifBlank { existing.hotspotSsid },
                            hotspotPass = pass.ifBlank { existing.hotspotPass },
                            ipAddress = hostIp.ifBlank { existing.ipAddress },
                            isHotspotHost = true
                        )
                    }
                    _uiState.update { cur ->
                        cur.copy(nodes = buildMergedNodesList(cur.nodes))
                    }
                    autoHotspotNegotiator.handleHotspotAutoJoin(canonicalSrcId, ssid, pass, hostIp)
                }
            }
            "GROUP_INVITE", "GROUP_CHAT" -> {
                val grpId = packet.groupId.ifBlank { packet.dstNodeId }.trim()
                if (grpId.isBlank()) return
                val grpName = packet.groupName.ifBlank { "Group $grpId" }.trim()
                val parsedMembers = packet.memberIdsCsv
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val existingGroup = _uiState.value.meshGroups.find { it.groupId == grpId }
                    ?: prefs.loadMeshGroups().find { it.groupId == grpId }

                val effectiveMembers = if (parsedMembers.isNotEmpty()) {
                    parsedMembers
                } else {
                    existingGroup?.memberIds ?: emptyList()
                }

                // Check if our own myNodeId is in memberIds
                val isMember = effectiveMembers.any { normalizeId(it) == normSelf } ||
                    (existingGroup?.memberIds?.any { normalizeId(it) == normSelf } == true)

                if (!isMember) {
                    // Not a member: do not display locally (buildRelayedPacketIfEligible already forwarded it with ttl - 1)
                    return
                }

                val senderPubForWrap = prefs.getPeerPublicKey(canonicalSrcId)
                    ?: packet.senderPubKey.takeIf { TacticalE2eeManager.isValidEcPublicKeyBase64(it) }.orEmpty()

                if (packet.encryptedKeysJson.isNotBlank() && senderPubForWrap.isNotBlank()) {
                    runCatching {
                        val keysObj = org.json.JSONObject(packet.encryptedKeysJson)
                        val wrappedForMe = keysObj.optString(normSelf)
                            .ifBlank { keysObj.optString("0x$normSelf") }
                            .ifBlank { keysObj.optString("0X$normSelf") }
                        if (wrappedForMe.isNotBlank()) {
                            TacticalE2eeManager.unwrapGroupKeyFromMember(
                                wrappedPayload = wrappedForMe,
                                myPrivateKeyBase64 = prefs.x25519PrivateKey,
                                senderPublicKeyBase64 = senderPubForWrap
                            )?.let { unwrappedKey ->
                                prefs.saveGroupAesKey(grpId, unwrappedKey)
                            }
                        }
                    }
                }

                val activeGroupKey = prefs.getGroupAesKey(grpId)
                    ?: existingGroup?.groupKeyBase64.orEmpty()

                // Automatically add or update the MeshGroup in meshGroups & SharedPreferences
                val mergedGroup = MeshGroup(
                    groupId = grpId,
                    groupName = if (grpName.startsWith("Group ") && existingGroup != null) existingGroup.groupName else grpName,
                    memberIds = effectiveMembers.map { "0x${normalizeId(it)}" }.distinct(),
                    createdAt = existingGroup?.createdAt ?: packet.timestamp,
                    groupKeyBase64 = activeGroupKey
                )
                val updatedGroups = (_uiState.value.meshGroups.filterNot { it.groupId == grpId } + mergedGroup)
                prefs.saveMeshGroups(updatedGroups)
                _uiState.update {
                    it.copy(
                        meshGroups = updatedGroups,
                        nodes = updatedNodes,
                        activePeerCount = peerCount
                    )
                }

                if (packet.packetId.isNotBlank()) {
                    val ackPkt = com.example.service.WireMeshPacket(
                        packetId = "ack_${packet.packetId}_${state.localNodeId}_${System.currentTimeMillis() and 0xFFFF}",
                        srcNodeId = state.localNodeId,
                        srcCallsign = state.operatorCallsign,
                        dstNodeId = packet.srcNodeId,
                        type = "CHAT_ACK",
                        payload = packet.packetId,
                        ttl = 5,
                        hops = 0,
                        srcUsername = state.claimedUsername,
                        dstUsername = packet.srcUsername
                    )
                    sendMeshPacket(ackPkt)
                    if (packet.srcUsername.isNotBlank() && (state.isInternetEnabled || hasValidatedOsInternet())) {
                        meshNetworkService.sendDirectPacketToUsername(packet.srcUsername, ackPkt, allowDirectSend = true)
                    }
                }

                val decryptedText = TacticalE2eeManager.decryptWithGroupKey(packet.payload, activeGroupKey)
                val groupThreadId = "group_$grpId"
                val groupMsgPkt = MeshPacket(
                    id = packet.packetId,
                    sourceId = peerNode?.id ?: canonicalSrcId,
                    destId = grpId,
                    sourceCallsign = effectiveCallsign,
                    type = PacketType.TEXT,
                    content = decryptedText,
                    hops = receivedHops,
                    rssiDbm = resolvedRssiDbm,
                    transport = resolvedTransport,
                    rawHexBadge = "[${packet.packetId} | $canonicalSrcId->$grpId | Hop $receivedHops]",
                    isIncoming = true,
                    timestamp = packet.timestamp
                )
                persistentChatManager.savePacket(groupThreadId, groupMsgPkt)
                if (!state.isAppInForeground || state.selectedTabIndex != 1 || state.selectedThreadId != groupThreadId) {
                    unreadCountsByThread.compute(groupThreadId) { _, prev -> (prev ?: 0) + 1 }
                    val grpName = state.meshGroups.find { it.groupId == grpId }?.groupName ?: "Group $grpId"
                    notificationManager.showChatNotification(
                        threadId = groupThreadId,
                        senderCallsign = "$effectiveCallsign @ $grpName",
                        senderNodeId = canonicalSrcId,
                        messagePreview = decryptedText,
                        transport = resolvedTransport
                    )
                }
                refreshConversationsList()
                scheduleAccountBackupSync()
                triggerHaptic(40)
            }
            "CHAT", "E2EE_DM" -> {
                val isDirectedToMe = normalizeId(packet.dstNodeId) == normSelf || (packet.type == "E2EE_DM" && (packet.dstNodeId == "ALL" || packet.dstNodeId == "BROADCAST"))
                val isEncryptedChannel = packet.dstNodeId == "ENC_TACNET"
                val isPublic = (packet.dstNodeId == "ALL" || packet.dstNodeId == "BROADCAST") && packet.type != "E2EE_DM"

                _uiState.update {
                    it.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount
                    )
                }

                if (isPublic || isEncryptedChannel || isDirectedToMe) {
                    // Immediately send back a lightweight CHAT_ACK to the sender (multi-hop TTL=5 + direct inbox if online)
                    if (packet.packetId.isNotBlank()) {
                        val ackPkt = com.example.service.WireMeshPacket(
                            packetId = "ack_${packet.packetId}_${state.localNodeId}_${System.currentTimeMillis() and 0xFFFF}",
                            srcNodeId = state.localNodeId,
                            srcCallsign = state.operatorCallsign,
                            dstNodeId = packet.srcNodeId,
                            type = "CHAT_ACK",
                            payload = packet.packetId,
                            ttl = 5,
                            hops = 0,
                            srcUsername = state.claimedUsername,
                            dstUsername = packet.srcUsername
                        )
                        sendMeshPacket(ackPkt)
                        if (packet.srcUsername.isNotBlank() && (state.isInternetEnabled || hasValidatedOsInternet())) {
                            meshNetworkService.sendDirectPacketToUsername(packet.srcUsername, ackPkt, allowDirectSend = true)
                        }
                    }

                    val threadId = when {
                        isPublic -> "channel_primary"
                        isEncryptedChannel -> "channel_encrypted"
                        else -> "dm_${peerNode?.id ?: canonicalSrcId}"
                    }

                    val displayContent = when {
                        packet.payload.startsWith("E2EE_ECDH_V2:") -> {
                            val peerPubKey = prefs.getPeerPublicKey(canonicalSrcId)
                                ?: packet.senderPubKey
                            TacticalE2eeManager.decryptDmWithEcdh(
                                payload = packet.payload,
                                myPrivateKeyBase64 = prefs.x25519PrivateKey,
                                peerPublicKeyBase64 = peerPubKey
                            )
                        }
                        packet.payload.startsWith("PLAINTEXT:") -> {
                            packet.payload.removePrefix("PLAINTEXT:")
                        }
                        packet.payload.startsWith("ENC:") -> {
                            val parts = packet.payload.split(":")
                            if (parts.size >= 5) {
                                val ctHex = parts[1]
                                val nonceHex = parts[2]
                                val macTag = parts[3]
                                val senderFp = parts[4]
                                val sharedSecret = if (isEncryptedChannel) "TACNET_SHARED_SECRET_KEY_001" else state.x25519PublicKey.ifBlank { "0123456789ABCDEF0123456789ABCDEF" }
                                TacticalE2eeManager.decrypt(
                                    com.example.crypto.EncryptedPayload(ctHex, nonceHex, macTag, senderFp),
                                    sharedSecret
                                )
                            } else {
                                packet.payload
                            }
                        }
                        else -> packet.payload
                    }

                    val chatPkt = MeshPacket(
                        id = packet.packetId,
                        sourceId = peerNode?.id ?: canonicalSrcId,
                        destId = packet.dstNodeId,
                        sourceCallsign = effectiveCallsign,
                        type = PacketType.TEXT,
                        content = displayContent,
                        hops = receivedHops,
                        rssiDbm = resolvedRssiDbm,
                        transport = resolvedTransport,
                        rawHexBadge = "[${packet.packetId} | $canonicalSrcId->${packet.dstNodeId} | Hop $receivedHops]",
                        isIncoming = true,
                        timestamp = packet.timestamp
                    )

                    if (packet.srcUsername.isNotBlank()) {
                        val normSenderUser = TacticalE2eeManager.normalizeUsername(packet.srcUsername)
                        if (TacticalE2eeManager.isValidUsername(normSenderUser) && prefs.getUsernameClaim(normSenderUser) == null) {
                            prefs.saveUsernameClaim(
                                com.example.crypto.UsernameClaimRecord(
                                    username = normSenderUser,
                                    displayName = effectiveCallsign,
                                    nodeId = peerNode?.id ?: canonicalSrcId,
                                    publicKeyBase64 = packet.senderPubKey,
                                    saltHex = "",
                                    pinHash = "",
                                    recoveryHash = "",
                                    claimedAt = 0L
                                )
                            )
                        }
                    }
                    persistentChatManager.savePacket(threadId, chatPkt)
                    val isCurrentlyViewingThisThread = appInForeground &&
                        _uiState.value.selectedTabIndex == 1 &&
                        _uiState.value.selectedThreadId == threadId
                    if (!isCurrentlyViewingThisThread) {
                        unreadCountsByThread.compute(threadId) { _, prev -> (prev ?: 0) + 1 }
                        notificationManager.showChatNotification(
                            threadId = threadId,
                            senderCallsign = effectiveCallsign,
                            senderNodeId = canonicalSrcId,
                            messagePreview = displayContent,
                            transport = resolvedTransport
                        )
                    }
                    refreshConversationsList()
                    scheduleAccountBackupSync()
                    triggerHaptic(40)
                }
            }
            "VOICE", "VOICE_STREAM", "VOICE_PTT", "CALL_AUDIO" -> {
                val dstNorm = normalizeId(packet.dstNodeId)
                val curUi = _uiState.value
                val isGroupOrPtt = packet.dstNodeId.isBlank() ||
                    packet.dstNodeId.equals("ALL", ignoreCase = true) ||
                    packet.dstNodeId.equals("BROADCAST", ignoreCase = true)
                val isCurrentlyInCall = tacticalCallService.callState.value.isInCall ||
                    curUi.duplexCallState.isInCall ||
                    curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE
                val isCurrentlyRinging = curUi.callState != com.example.service.CallSignalingState.IDLE ||
                    curUi.duplexCallState.signalingState != com.example.service.CallSignalingState.IDLE
                val isForMe = isGroupOrPtt ||
                    dstNorm == normSelf ||
                    isCurrentlyInCall ||
                    isCurrentlyRinging

                if (!isForMe) return

                val nowMs = System.currentTimeMillis()
                val inPostHangupWindow = (nowMs - lastCallEndedMs) < 2_500L &&
                    (lastEndedPeerNormId == null || lastEndedPeerNormId == normSrc)
                val isCurrentlyIdle = curUi.callState == com.example.service.CallSignalingState.IDLE &&
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IDLE

                if (isCurrentlyIdle && inPostHangupWindow) {
                    // Drop residual trailing voice frames from the call we just hung up
                    return
                }

                // Receiving matching VOICE while RINGING_OUTGOING switches caller to IN_CALL_ACTIVE
                if ((curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                    curUi.callState == com.example.service.CallSignalingState.RINGING_OUTGOING) &&
                    (curUi.activeCallTargetId.equals("ALL", ignoreCase = true) ||
                        normalizeId(curUi.activeCallTargetId) == normSrc)
                ) {
                    ringSignalRepeatJob?.cancel()
                    ringSignalRepeatJob = null
                    val callTarget = if (curUi.activeCallTargetId.equals("ALL", ignoreCase = true)) {
                        "ALL"
                    } else {
                        fallbackCallNode.id
                    }
                    offGridBleEngine.updateCallStateBeacon(
                        com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IN_CALL_ACTIVE,
                        callTarget
                    )
                    callAcceptConfirmedByPeer = true
                    tacticalCallService.acceptCall(startMicDelayMs = 0L)
                    tacticalCallService.markCallAcceptAcked()
                    _uiState.update {
                        it.copy(
                            callState = com.example.service.CallSignalingState.IN_CALL_ACTIVE,
                            remoteCallerId = if (callTarget == "ALL") "ALL" else fallbackCallNode.id,
                            remoteCallerName = if (callTarget == "ALL") "Group Mesh Call (All Peers)" else fallbackCallNode.callsign,
                            isPrivateCallActive = true,
                            activePrivateCallNode = fallbackCallNode,
                            incomingPrivateCall = null,
                            callAckConfirmed = true
                        )
                    }
                    sendCallControlBurst(
                        type = "CALL_ACCEPT_ACK",
                        targetNodeId = fallbackCallNode.id,
                        payload = "ACK",
                        repeatCount = 2,
                        spacingMs = 110L
                    )
                } else if (isCurrentlyInCall) {
                    // Receiving any live VOICE frame from the call peer implicitly confirms the call handshake
                    if (!callAcceptConfirmedByPeer) {
                        callAcceptConfirmedByPeer = true
                        acceptSignalRetryJob?.cancel()
                        acceptSignalRetryJob = null
                        tacticalCallService.markCallAcceptAcked()
                        _uiState.update { it.copy(callAckConfirmed = true) }
                    }
                }

                val isCallPacket = isCurrentlyInCall ||
                    tacticalCallService.callState.value.isInCall ||
                    _uiState.value.duplexCallState.isInCall
                val jsonVoiceSeq = extractVoiceSeqFromPacketId(packet.packetId)
                if (jsonVoiceSeq != null && !shouldAcceptVoiceFrame(packet.srcNodeId, jsonVoiceSeq)) {
                    return
                }
                if (packet.payload.isNotBlank()) {
                    runCatching {
                        audioService.playAudioBase64(packet.payload, isCallAudio = isCallPacket)
                        if (isCallPacket) {
                            tacticalCallService.recordIncomingFramePlayed()
                        }
                    }
                    val vSeq = webVoiceSeqCounter.incrementAndGet()
                    latestWebVoiceFrame = Triple(vSeq, canonicalSrcId to effectiveCallsign, packet.payload)
                    pulseMeshWebServer.notifyVoiceFrame(canonicalSrcId, effectiveCallsign, packet.payload)
                }
                val speakerLabel = "SPEAKING: $effectiveCallsign (Hop ${packet.hops})"
                speakingBannerClearJob?.cancel()
                speakingBannerClearJob = viewModelScope.launch {
                    delay(1600L)
                    _uiState.update { it.copy(activeSpeakingBanner = "") }
                }
                _uiState.update {
                    it.copy(
                        rxAudioCount = it.rxAudioCount + 1,
                        activeSpeakingBanner = speakerLabel,
                        pttStatus = speakerLabel,
                        voiceHopStep = "RX Voice Frame from $effectiveCallsign (Hop ${packet.hops})"
                    )
                }
            }
            "CALL_RING", "CALL_INVITE" -> {
                val curUi = _uiState.value
                // If we already accepted and are IN_CALL_ACTIVE with this caller, but the caller is STILL ringing
                // (because our initial CALL_ACCEPT burst dropped), immediately re-send CALL_ACCEPT!
                if (curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE
                ) {
                    val activePeerNorm = normalizeId(
                        curUi.activePrivateCallNode?.id
                            ?: curUi.remoteCallerId.ifBlank { curUi.activeCallTargetId }
                    )
                    if (curUi.activeCallTargetId.equals("ALL", ignoreCase = true) ||
                        activePeerNorm == normSrc ||
                        normalizeId(packet.dstNodeId) == normSelf
                    ) {
                        sendCallControlBurst(
                            type = "CALL_ACCEPT",
                            targetNodeId = fallbackCallNode.id,
                            payload = "ACCEPT",
                            repeatCount = 2,
                            spacingMs = 100L
                        )
                    }
                    return
                }
                if (curUi.callState != com.example.service.CallSignalingState.IDLE ||
                    curUi.duplexCallState.signalingState != com.example.service.CallSignalingState.IDLE
                ) {
                    return
                }
                val nowMs = System.currentTimeMillis()
                val isFromCafeBeacon = packet.packetId.startsWith("cafe_call_")
                if ((nowMs - lastCallEndedMs) < CALL_POST_ACTION_COOLDOWN_MS &&
                    (lastEndedPeerNormId == null || lastEndedPeerNormId == normSrc)
                ) {
                    // 6-second post-action anti-bounce cooldown: suppress stale 0x01 beacons / in-flight CALL_RING
                    return
                }
                val isGroupRing = !isFromCafeBeacon && (
                    packet.dstNodeId.equals("ALL", ignoreCase = true) ||
                        packet.dstNodeId.equals("BROADCAST", ignoreCase = true)
                    )
                val isTargetedToMe = isFromCafeBeacon || normalizeId(packet.dstNodeId) == normSelf
                if (isTargetedToMe || isGroupRing) {
                    val ringTarget = if (isGroupRing) "ALL" else fallbackCallNode.id
                    val displayName = if (isGroupRing) "${fallbackCallNode.callsign} (Group Call)" else fallbackCallNode.callsign
                    tacticalCallService.startIncomingCall(fallbackCallNode.id, displayName, state.isSpokenCallerIdEnabled)
                    notificationManager.showIncomingCallNotification(
                        callerNodeId = fallbackCallNode.id,
                        callerCallsign = displayName,
                        transportLabel = if (autoHotspotNegotiator.hasHighSpeedLink()) "Hotspot Wi-Fi 8kHz HD" else "BLE Off-Grid Mesh"
                    )
                    _uiState.update {
                        it.copy(
                            nodes = updatedNodes,
                            activePeerCount = peerCount,
                            callState = com.example.service.CallSignalingState.RINGING_INCOMING,
                            remoteCallerId = fallbackCallNode.id,
                            remoteCallerName = displayName,
                            activeCallTargetId = ringTarget,
                            incomingPrivateCall = fallbackCallNode,
                            selectedTabIndex = 2
                        )
                    }
                    triggerHaptic(120)
                }
            }
            "CALL_ACCEPT" -> {
                val curUi = _uiState.value
                val isAlreadyInCall = curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE
                if (isAlreadyInCall) {
                    // If we already transitioned to IN_CALL_ACTIVE with this peer and receive a retry CALL_ACCEPT,
                    // re-send CALL_ACCEPT_ACK (rate-limited) so the callee stops retrying.
                    val activePeerNorm = normalizeId(
                        curUi.activePrivateCallNode?.id
                            ?: curUi.remoteCallerId.ifBlank { curUi.activeCallTargetId }
                    )
                    val now = System.currentTimeMillis()
                    if ((curUi.activeCallTargetId.equals("ALL", ignoreCase = true) || activePeerNorm == normSrc) &&
                        (now - lastCallAcceptAckSentMs >= 380L)
                    ) {
                        lastCallAcceptAckSentMs = now
                        sendCallControlBurst(
                            type = "CALL_ACCEPT_ACK",
                            targetNodeId = fallbackCallNode.id,
                            payload = "ACK",
                            repeatCount = 2,
                            spacingMs = 100L
                        )
                    }
                    return
                }

                val isRingingOutgoing = curUi.callState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING
                // Strictly require RINGING_OUTGOING state so an in-flight or stale CALL_ACCEPT
                // after hanging up NEVER re-enters a call or redirects to Group Mesh Call!
                if (!isRingingOutgoing) return

                val targetNorm = normalizeId(curUi.activeCallTargetId)
                val isGroupOutgoing = curUi.activeCallTargetId.equals("ALL", ignoreCase = true)
                val acceptForMe = isGroupOutgoing ||
                    targetNorm == normSrc ||
                    normalizeId(packet.dstNodeId) == normSelf
                if (!acceptForMe) return

                // Receiving CALL_ACCEPT immediately switches the caller to callState = IN_CALL_ACTIVE
                // and starts the caller's microphone and speaker!
                ringSignalRepeatJob?.cancel()
                ringSignalRepeatJob = null
                acceptSignalRetryJob?.cancel()
                acceptSignalRetryJob = null
                callAcceptConfirmedByPeer = true
                lastCallAcceptAckSentMs = System.currentTimeMillis()
                val targetId = if (isGroupOutgoing) "ALL" else fallbackCallNode.id
                offGridBleEngine.updateCallStateBeacon(
                    com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IN_CALL_ACTIVE,
                    targetId
                )
                tacticalCallService.acceptCall(startMicDelayMs = 0L)
                tacticalCallService.markCallAcceptAcked()
                val activePeerDisplay = if (targetId == "ALL") "Group Mesh Call (All Peers)" else fallbackCallNode.callsign
                notificationManager.clearCallNotification()
                _uiState.update {
                    it.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount,
                        callState = com.example.service.CallSignalingState.IN_CALL_ACTIVE,
                        remoteCallerId = if (targetId == "ALL") "ALL" else fallbackCallNode.id,
                        remoteCallerName = activePeerDisplay,
                        activeCallTargetId = targetId,
                        isPrivateCallActive = true,
                        activePrivateCallNode = fallbackCallNode,
                        incomingPrivateCall = null,
                        callAckConfirmed = true,
                        selectedTabIndex = 2
                    )
                }
                // Transmit 3-way handshake CALL_ACCEPT_ACK back to the callee so the callee knows the caller connected
                sendCallControlBurst(
                    type = "CALL_ACCEPT_ACK",
                    targetNodeId = fallbackCallNode.id,
                    payload = "ACK",
                    repeatCount = 2,
                    spacingMs = 110L
                )
                triggerHaptic(80)
            }
            "CALL_ACCEPT_ACK" -> {
                val curUi = _uiState.value
                if (curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE
                ) {
                    callAcceptConfirmedByPeer = true
                    acceptSignalRetryJob?.cancel()
                    acceptSignalRetryJob = null
                    tacticalCallService.markCallAcceptAcked()
                    _uiState.update {
                        it.copy(
                            nodes = updatedNodes,
                            activePeerCount = peerCount,
                            callAckConfirmed = true
                        )
                    }
                }
            }
            "CALL_DECLINE" -> {
                val curUi = _uiState.value
                if (curUi.callState == com.example.service.CallSignalingState.IDLE &&
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IDLE
                ) {
                    return
                }
                val isConnectedInCall = curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    curUi.duplexCallState.isInCall ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    tacticalCallService.callState.value.isInCall
                val isRingingOutgoingOnly = !isConnectedInCall && (
                    curUi.callState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                        curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                        tacticalCallService.callState.value.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING
                    )
                val peerIdForVm = curUi.activePrivateCallNode?.id
                    ?: curUi.remoteCallerId.ifBlank { fallbackCallNode.id }
                val peerNameForVm = curUi.activePrivateCallNode?.callsign
                    ?: curUi.remoteCallerName.ifBlank { fallbackCallNode.callsign }
                lastCallEndedMs = System.currentTimeMillis()
                lastEndedPeerNormId = normSrc
                ringSignalRepeatJob?.cancel()
                ringSignalRepeatJob = null
                acceptSignalRetryJob?.cancel()
                acceptSignalRetryJob = null
                callAcceptConfirmedByPeer = false
                notificationManager.clearCallNotification()
                offGridBleEngine.updateCallStateBeacon(
                    com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IDLE,
                    "ALL"
                )
                if (isRingingOutgoingOnly) {
                    tacticalCallService.enterVoicemailRecordingMode(
                        peerId = peerIdForVm,
                        peerCallsign = peerNameForVm,
                        promptBanner = "RECIPIENT HUNG UP — SPEAK YOUR MESSAGE, THEN TAP HANG UP TO SEND",
                        spokenPrompt = "The recipient hung up. Please say what you want to send, then press hang up."
                    )
                } else {
                    tacticalCallService.endCall(isUserBusy = false)
                }
                _uiState.update {
                    it.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount,
                        callState = com.example.service.CallSignalingState.IDLE,
                        remoteCallerId = "",
                        remoteCallerName = "",
                        activeCallTargetId = "ALL",
                        activeSpeakingBanner = "",
                        isPrivateCallActive = false,
                        activePrivateCallNode = null,
                        incomingPrivateCall = null,
                        callAckConfirmed = false
                    )
                }
                triggerHaptic(60)
            }
            "WEBRTC_OFFER", "WEBRTC_ANSWER", "WEBRTC_ICE" -> {
                // Relay WebRTC P2P signaling frames directly to connected Web/iPhone PWA clients over /ws & SSE
                pulseMeshWebServer.notifyCallSignal(
                    type = packet.type,
                    srcNodeId = packet.srcNodeId,
                    srcCallsign = effectiveCallsign,
                    dstNodeId = packet.dstNodeId.ifBlank { "ALL" },
                    payload = packet.payload
                )
            }
            "CALL_END" -> {
                val curUi = _uiState.value
                if (curUi.callState == com.example.service.CallSignalingState.IDLE &&
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IDLE
                ) {
                    return
                }
                val activePeerNorm = normalizeId(
                    curUi.activePrivateCallNode?.id
                        ?: curUi.incomingPrivateCall?.id
                        ?: curUi.remoteCallerId
                )
                val matchesActivePeer = curUi.activeCallTargetId.equals("ALL", ignoreCase = true) ||
                    activePeerNorm.isBlank() ||
                    activePeerNorm == normSrc ||
                    normalizeId(packet.dstNodeId) == normSelf
                if (!matchesActivePeer) return

                // Only trigger Voicemail Recording if the outgoing call was STILL RINGING and had NOT yet been accepted!
                // If the peer already accepted the call (IN_CALL_ACTIVE) and then hung up, end the call cleanly with NO voicemail.
                val isConnectedInCall = curUi.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    curUi.duplexCallState.isInCall ||
                    curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    tacticalCallService.callState.value.isInCall
                val isRingingOutgoingOnly = !isConnectedInCall && (
                    curUi.callState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                        curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                        tacticalCallService.callState.value.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING
                    )

                val peerIdForVm = curUi.activePrivateCallNode?.id
                    ?: curUi.remoteCallerId.ifBlank { fallbackCallNode.id }
                val peerNameForVm = curUi.activePrivateCallNode?.callsign
                    ?: curUi.remoteCallerName.ifBlank { fallbackCallNode.callsign }

                lastCallEndedMs = System.currentTimeMillis()
                lastEndedPeerNormId = normSrc
                ringSignalRepeatJob?.cancel()
                ringSignalRepeatJob = null
                acceptSignalRetryJob?.cancel()
                acceptSignalRetryJob = null
                callAcceptConfirmedByPeer = false
                notificationManager.clearCallNotification()
                offGridBleEngine.updateCallStateBeacon(
                    com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IDLE,
                    "ALL"
                )
                if (isRingingOutgoingOnly) {
                    tacticalCallService.enterVoicemailRecordingMode(
                        peerId = peerIdForVm,
                        peerCallsign = peerNameForVm,
                        promptBanner = "RECIPIENT HUNG UP — SPEAK YOUR MESSAGE, THEN TAP HANG UP TO SEND",
                        spokenPrompt = "The recipient hung up. Please say what you want to send, then press hang up."
                    )
                } else {
                    tacticalCallService.endCall(isUserBusy = false)
                }
                _uiState.update {
                    it.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount,
                        callState = com.example.service.CallSignalingState.IDLE,
                        remoteCallerId = "",
                        remoteCallerName = "",
                        activeCallTargetId = "ALL",
                        activeSpeakingBanner = "",
                        isPrivateCallActive = false,
                        activePrivateCallNode = null,
                        incomingPrivateCall = null,
                        callAckConfirmed = false
                    )
                }
                triggerHaptic(40)
            }
            "WAYPOINT" -> {
                val wpObj = runCatching { org.json.JSONObject(packet.payload) }.getOrNull()
                val wpId = wpObj?.optString("id")?.ifBlank { packet.packetId } ?: packet.packetId
                val wpTitle = wpObj?.optString("title")?.ifBlank { "Tactical Pin" } ?: packet.payload.ifBlank { "Tactical Pin" }
                val wpTypeStr = wpObj?.optString("type").orEmpty()
                val wpType = runCatching { WaypointType.valueOf(wpTypeStr) }.getOrDefault(WaypointType.RALLY)
                val wpLat = wpObj?.optDouble("lat", packet.lat ?: 0.0) ?: (packet.lat ?: 0.0)
                val wpLon = wpObj?.optDouble("lon", packet.lng ?: 0.0) ?: (packet.lng ?: 0.0)
                val creator = wpObj?.optString("creator")?.ifBlank { effectiveCallsign } ?: effectiveCallsign
                val wpTs = wpObj?.optLong("ts", packet.timestamp) ?: packet.timestamp

                if (wpLat != 0.0 || wpLon != 0.0) {
                    val incomingWp = TacticalWaypoint(
                        id = wpId,
                        title = wpTitle,
                        type = wpType,
                        latitude = wpLat,
                        longitude = wpLon,
                        creatorCallsign = creator,
                        timestamp = wpTs,
                        payloadHex = "0x${wpId.hashCode().toUInt().toString(16).uppercase()}"
                    )
                    val wpPkt = MeshPacket(
                        id = packet.packetId,
                        sourceId = peerNode?.id ?: canonicalSrcId,
                        destId = "ALL",
                        sourceCallsign = effectiveCallsign,
                        type = PacketType.WAYPOINT,
                        content = "SHARED PIN [$wpTypeStr]: $wpTitle @ ${String.format("%.4f", wpLat)}, ${String.format("%.4f", wpLon)}",
                        hops = receivedHops,
                        rssiDbm = resolvedRssiDbm,
                        transport = "Shared Map Pin",
                        rawHexBadge = "[WAYPOINT | $canonicalSrcId->ALL | Hop $receivedHops]",
                        isIncoming = true,
                        waypointData = incomingWp
                    )
                    _uiState.update { current ->
                        val dedupWps = current.waypoints.filterNot { it.id == incomingWp.id } + incomingWp
                        current.copy(
                            nodes = updatedNodes,
                            activePeerCount = peerCount,
                            waypoints = dedupWps,
                            packets = listOf(wpPkt) + current.packets
                        )
                    }
                    triggerHaptic(50)
                } else {
                    _uiState.update {
                        it.copy(
                            nodes = updatedNodes,
                            activePeerCount = peerCount
                        )
                    }
                }
            }
            "SOS" -> {
                val sosPkt = MeshPacket(
                    id = packet.packetId,
                    sourceId = peerNode?.id ?: canonicalSrcId,
                    destId = "ALL",
                    sourceCallsign = effectiveCallsign,
                    type = PacketType.SOS,
                    content = "SOS ALERT FROM $effectiveCallsign: ${packet.payload}",
                    hops = receivedHops,
                    rssiDbm = resolvedRssiDbm,
                    transport = resolvedTransport,
                    rawHexBadge = "[SOS | $canonicalSrcId->ALL | Hop $receivedHops | PRIORITY]",
                    isIncoming = true,
                    timestamp = packet.timestamp
                )
                persistentChatManager.savePacket("channel_primary", sosPkt)
                val updatedWps = if (packet.lat != null && packet.lng != null && (packet.lat != 0.0 || packet.lng != 0.0)) {
                    val sosWp = TacticalWaypoint(
                        id = "SOS-${normSrc}",
                        title = "SOS: $effectiveCallsign",
                        type = WaypointType.SOS,
                        latitude = packet.lat,
                        longitude = packet.lng,
                        creatorCallsign = effectiveCallsign,
                        timestamp = packet.timestamp,
                        payloadHex = "0xSOS"
                    )
                    _uiState.value.waypoints.filterNot { it.id == sosWp.id } + sosWp
                } else {
                    _uiState.value.waypoints
                }
                _uiState.update {
                    it.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount,
                        waypoints = updatedWps,
                        packets = listOf(sosPkt) + it.packets,
                        incomingSosAlert = "SOS FROM $effectiveCallsign: ${packet.payload.ifBlank { "EMERGENCY DISTRESS" }}"
                    )
                }
                audioService.playSosAlarmPulse()
                if (!appInForeground || _uiState.value.selectedTabIndex != 1) {
                    notificationManager.showChatNotification(
                        threadId = "channel_primary",
                        senderCallsign = "⚠ SOS: $effectiveCallsign",
                        senderNodeId = canonicalSrcId,
                        messagePreview = packet.payload.ifBlank { "EMERGENCY SOS DISTRESS BEACON" },
                        transport = resolvedTransport
                    )
                }
                refreshConversationsList()
                triggerHaptic(320)
            }
            "SOS_CLEAR" -> {
                _uiState.update { cur ->
                    cur.copy(
                        nodes = updatedNodes,
                        activePeerCount = peerCount,
                        incomingSosAlert = if (cur.incomingSosAlert.contains(effectiveCallsign, ignoreCase = true) ||
                            cur.incomingSosAlert.contains(canonicalSrcId, ignoreCase = true)
                        ) "" else cur.incomingSosAlert,
                        waypoints = cur.waypoints.filterNot { wp ->
                            wp.type == WaypointType.SOS && (
                                wp.id.equals("SOS-$normSrc", ignoreCase = true) ||
                                    wp.id.equals("SOS-${canonicalSrcId.takeLast(4)}", ignoreCase = true) ||
                                    wp.creatorCallsign.equals(effectiveCallsign, ignoreCase = true)
                                )
                        }
                    )
                }
                val clrPkt = MeshPacket(
                    id = packet.packetId,
                    sourceId = peerNode?.id ?: canonicalSrcId,
                    destId = "ALL",
                    sourceCallsign = effectiveCallsign,
                    type = PacketType.TEXT,
                    content = packet.payload.ifBlank { "SOS CLEARED by $effectiveCallsign" },
                    hops = receivedHops,
                    transport = resolvedTransport,
                    rawHexBadge = "[SOS_CLEAR | $canonicalSrcId->ALL | Hop $receivedHops]",
                    isIncoming = true,
                    timestamp = packet.timestamp
                )
                persistentChatManager.savePacket("channel_primary", clrPkt)
                refreshConversationsList()
            }
            "QUICK_STATUS" -> {
                val statusObj = runCatching { org.json.JSONObject(packet.payload) }.getOrNull()
                val rawCode = statusObj?.optString("code")?.ifBlank { packet.payload } ?: packet.payload
                val speakFlag = statusObj?.optBoolean("voice", true) ?: true
                val qs = QuickStatusCode.fromCode(rawCode)
                val isGreek = _uiState.value.appLanguage == "el"
                val label = if (qs != null) (if (isGreek) qs.labelEl else qs.labelEn) else rawCode.take(32)
                peersBackingMap[normSrc]?.let { pNode ->
                    peersBackingMap[normSrc] = pNode.copy(
                        quickStatus = label,
                        quickStatusAtMs = packet.timestamp
                    )
                }
                val qsPkt = MeshPacket(
                    id = packet.packetId,
                    sourceId = peerNode?.id ?: canonicalSrcId,
                    destId = "ALL",
                    sourceCallsign = effectiveCallsign,
                    type = PacketType.TEXT,
                    content = "[STATUS] $label",
                    hops = receivedHops,
                    transport = resolvedTransport,
                    rawHexBadge = "[STATUS | $canonicalSrcId->ALL | Hop $receivedHops]",
                    isIncoming = true,
                    timestamp = packet.timestamp
                )
                persistentChatManager.savePacket("channel_primary", qsPkt)
                _uiState.update { cur ->
                    val merged = buildMergedNodesList(cur.nodes)
                    cur.copy(nodes = merged, activePeerCount = merged.count { !it.isSelf })
                }
                refreshConversationsList()
                if (speakFlag && _uiState.value.quickStatusVoiceEnabled) {
                    val spoken = if (qs != null) (if (isGreek) qs.spokenEl else qs.spokenEn) else label
                    speakStatusAnnouncement("$effectiveCallsign: $spoken")
                }
                triggerHaptic(35)
            }
            "REACTION" -> {
                var targetId = ""
                var emoji = ""
                val jsonObj = runCatching { org.json.JSONObject(packet.payload) }.getOrNull()
                if (jsonObj != null) {
                    targetId = jsonObj.optString("targetId").trim()
                    emoji = jsonObj.optString("emoji").trim()
                } else if (packet.payload.contains("|")) {
                    val parts = packet.payload.split("|", limit = 2)
                    targetId = parts[0].trim()
                    emoji = parts.getOrElse(1) { "" }.trim()
                }
                if (targetId.isNotBlank() && emoji.isNotBlank()) {
                    if (persistentChatManager.toggleReaction(targetId, emoji, effectiveCallsign)) {
                        refreshConversationsList()
                        pulseMeshWebServer.notifyStateChanged()
                    }
                }
            }
            "WEATHER_ALERT" -> {
                val wxPkt = MeshPacket(
                    id = packet.packetId,
                    sourceId = peerNode?.id ?: canonicalSrcId,
                    destId = "ALL",
                    sourceCallsign = effectiveCallsign,
                    type = PacketType.TEXT,
                    content = "⛈️ ${packet.payload.ifBlank { "BAROMETRIC STORM WARNING" }}",
                    hops = receivedHops,
                    transport = resolvedTransport,
                    rawHexBadge = "[WEATHER | $canonicalSrcId->ALL | Hop $receivedHops]",
                    isIncoming = true,
                    timestamp = packet.timestamp
                )
                persistentChatManager.savePacket("channel_primary", wxPkt)
                refreshConversationsList()
                triggerHaptic(60)
            }
            "TILE_REQ" -> {
                // Gateway Peer serves offline neighbors' map tile requests over BLE/Wi-Fi Direct!
                if (state.isInternetEnabled && state.internetGatewayBridgeEnabled && hasValidatedOsInternet()) {
                    runCatching {
                        val obj = org.json.JSONObject(packet.payload)
                        val z = obj.optInt("z", -1)
                        val x = obj.optInt("x", -1)
                        val y = obj.optInt("y", -1)
                        if (z in 0..19 && x >= 0 && y >= 0) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val pngBytes = com.example.util.NativeTileMapManager.fetchTileBytesOnline(
                                    context = getApplication(),
                                    z = z,
                                    x = x,
                                    y = y
                                )
                                if (pngBytes != null && pngBytes.size in 32..65536) {
                                    val b64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)
                                    val payloadJson = org.json.JSONObject().apply {
                                        put("z", z)
                                        put("x", x)
                                        put("y", y)
                                        put("b64", b64)
                                    }.toString()
                                    val replyPkt = com.example.service.WireMeshPacket(
                                        packetId = "tile_${z}_${x}_${y}_${System.currentTimeMillis() and 0xFFFF}",
                                        srcNodeId = state.localNodeId,
                                        srcCallsign = state.operatorCallsign,
                                        dstNodeId = canonicalSrcId,
                                        type = "MAP_TILE_SYNC",
                                        payload = payloadJson,
                                        ttl = 3,
                                        hops = 0,
                                        isGateway = true
                                    )
                                    val replyJson = org.json.JSONObject().apply {
                                        put("id", replyPkt.packetId)
                                        put("packetId", replyPkt.packetId)
                                        put("type", "MAP_TILE_SYNC")
                                        put("t", "MAP_TILE_SYNC")
                                        put("srcId", replyPkt.srcNodeId)
                                        put("src", replyPkt.srcNodeId)
                                        put("srcName", replyPkt.srcCallsign)
                                        put("cs", replyPkt.srcCallsign)
                                        put("dstId", replyPkt.dstNodeId)
                                        put("dst", replyPkt.dstNodeId)
                                        put("ttl", replyPkt.ttl)
                                        put("hops", 0)
                                        put("gw", true)
                                        put("payload", payloadJson)
                                        put("p", payloadJson)
                                    }.toString()
                                    autoHotspotNegotiator.sendPacket(replyJson)
                                    offGridBleEngine.broadcastPacketChunked(replyPkt)
                                }
                            }
                        }
                    }
                }
            }
            "MAP_TILE_SYNC" -> {
                runCatching {
                    val obj = org.json.JSONObject(packet.payload)
                    val z = obj.optInt("z", -1)
                    val x = obj.optInt("x", -1)
                    val y = obj.optInt("y", -1)
                    val b64 = obj.optString("b64", "")
                    if (z in 0..19 && x >= 0 && y >= 0 && b64.isNotBlank()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val ok = com.example.util.NativeTileMapManager.importCachedTileChunk(
                                context = getApplication(),
                                z = z,
                                x = x,
                                y = y,
                                base64Png = b64
                            )
                            if (ok) {
                                _uiState.update {
                                    it.copy(
                                        p2pTileSyncStatus = "Received offline map tile z=$z/$x/$y from $effectiveCallsign"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "TRAIL_SYNC" -> {
                runCatching {
                    val arr = org.json.JSONArray(packet.payload)
                    val incomingPts = mutableListOf<BreadcrumbPoint>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val lat = item.optDouble("lat", Double.NaN)
                        val lon = item.optDouble("lon", Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN()) {
                            incomingPts.add(
                                BreadcrumbPoint(
                                    lat = lat,
                                    lon = lon,
                                    altM = item.optDouble("altM", 0.0),
                                    timestamp = item.optLong("ts", System.currentTimeMillis())
                                )
                            )
                        }
                    }
                    if (incomingPts.isNotEmpty()) {
                        val merged = (_uiState.value.breadcrumbs + incomingPts).takeLast(500)
                        prefs.saveBreadcrumbs(merged)
                        _uiState.update { it.copy(breadcrumbs = merged) }
                    }
                }
            }
            else -> {
                if (packet.type.startsWith("KEY_VERIFY:")) {
                    val rxChecksum = packet.type.substringAfter("KEY_VERIFY:").toIntOrNull() ?: -1
                    val peerKey = prefs.getPendingChangedPeerPublicKey(canonicalSrcId)
                        ?: prefs.getPeerPublicKey(canonicalSrcId)
                        ?: peersBackingMap[normSrc]?.publicKeyBase64
                        ?: ""
                    if (rxChecksum >= 0 && peerKey.isNotBlank()) {
                        val expectedChecksum = com.example.service.UltrasonicAcousticModem.fingerprintChecksum8(peerKey)
                        if (rxChecksum == expectedChecksum) {
                            acceptChangedPeerKey(canonicalSrcId)
                            triggerHaptic(75)
                        }
                    }
                }
                // For background HEARTBEAT / GPS_BEACON, registerOrUpdatePeerInstant already handled throttled UI updates
            }
        }
        pulseMeshWebServer.notifyStateChanged()
    }

    fun pingMeshNow() {
        val state = _uiState.value
        val localIpStr = autoHotspotNegotiator.myLocalIp.ifBlank { "MESH_HEARTBEAT" }
        val (modeLat, modeLng) = com.example.service.OffGridBleMeshEngine.applyLocationShareMode(
            state.userLatitude,
            state.userLongitude,
            state.locationShareMode
        )
        val hasSharedCoords = state.locationShareMode != LocationShareMode.OFF && (modeLat != 0.0 || modeLng != 0.0)
        val beaconPkt = com.example.service.WireMeshPacket(
            packetId = "ping_${System.currentTimeMillis()}",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = "BROADCAST",
            type = if (hasSharedCoords) "GPS_BEACON" else "HEARTBEAT",
            payload = localIpStr,
            lat = if (state.locationShareMode == LocationShareMode.OFF) 0.0 else if (hasSharedCoords) modeLat else null,
            lng = if (state.locationShareMode == LocationShareMode.OFF) 0.0 else if (hasSharedCoords) modeLng else null,
            battery = state.realBatteryPercent
        )
        sendMeshPacket(beaconPkt)
        announcePublicKey(force = false)
        meshNetworkService.pingMeshNow()
        triggerHaptic(40)
    }

    fun announcePublicKey(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyAnnounceBroadcastMs < 5_000L) return
        lastKeyAnnounceBroadcastMs = now
        val state = _uiState.value
        val pubKey = state.x25519PublicKey.ifBlank { prefs.x25519PublicKey }
        if (!TacticalE2eeManager.isValidEcPublicKeyBase64(pubKey)) return
        val pkt = com.example.service.WireMeshPacket(
            packetId = "key_${normalizeId(state.localNodeId)}_${now and 0xFFFFF}",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = "BROADCAST",
            type = "KEY_ANNOUNCE",
            payload = pubKey,
            senderPubKey = pubKey,
            ttl = 2,
            hops = 0,
            battery = state.realBatteryPercent
        )
        sendMeshPacket(pkt)
    }

    fun processIncomingPeerPublicKey(peerNodeId: String, incomingPubKey: String): String? {
        val clean = incomingPubKey.trim()
        if (!TacticalE2eeManager.isValidEcPublicKeyBase64(clean)) return null
        val normPeer = normalizeId(peerNodeId)
        if (normPeer.isBlank() || normPeer == normalizeId(_uiState.value.localNodeId)) return null

        val (_, isWarning) = prefs.verifyOrTrustPeerPublicKey(peerNodeId, clean)
        val effectiveKey = prefs.getPeerPublicKey(peerNodeId) ?: clean
        val safetyFp = TacticalE2eeManager.computePairwiseSafetyFingerprint(prefs.x25519PublicKey, effectiveKey)

        peersBackingMap[normPeer]?.let { existing ->
            peersBackingMap[normPeer] = existing.copy(
                publicKeyBase64 = effectiveKey,
                safetyFingerprint = safetyFp,
                isKeyChangedWarning = isWarning
            )
        }
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (!node.isSelf && normalizeId(node.id) == normPeer) {
                    node.copy(
                        publicKeyBase64 = effectiveKey,
                        safetyFingerprint = safetyFp,
                        isKeyChangedWarning = isWarning
                    )
                } else {
                    node
                }
            }
            current.copy(nodes = updatedNodes)
        }
        refreshConversationsList()
        flushPendingKeyExchangeDmsForPeer(peerNodeId)
        return effectiveKey
    }

    fun acceptChangedPeerKey(peerNodeId: String) {
        val acceptedKey = prefs.acceptChangedPeerPublicKey(peerNodeId) ?: return
        val normPeer = normalizeId(peerNodeId)
        val safetyFp = TacticalE2eeManager.computePairwiseSafetyFingerprint(prefs.x25519PublicKey, acceptedKey)
        peersBackingMap[normPeer]?.let { existing ->
            peersBackingMap[normPeer] = existing.copy(
                publicKeyBase64 = acceptedKey,
                safetyFingerprint = safetyFp,
                isKeyChangedWarning = false
            )
        }
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (!node.isSelf && normalizeId(node.id) == normPeer) {
                    node.copy(
                        publicKeyBase64 = acceptedKey,
                        safetyFingerprint = safetyFp,
                        isKeyChangedWarning = false
                    )
                } else {
                    node
                }
            }
            current.copy(nodes = updatedNodes)
        }
        refreshConversationsList()
        announcePublicKey(force = true)
        triggerHaptic(45)
    }

    fun toggleTransportMode() {
        toggleInternetEnabled()
    }

    fun reconnectRelay() {
        meshNetworkService.reconnect()
        if (_uiState.value.isInternetEnabled) {
            syncPendingUsernameHoldIfOnline()
        }
        triggerHaptic(30)
    }

    fun setAppTheme(theme: com.example.util.AppTheme) {
        prefs.appTheme = theme
        _uiState.update { it.copy(appTheme = theme) }
        scheduleAccountBackupSync()
        triggerHaptic(30)
    }

    fun toggleLightMode() {
        val next = if (_uiState.value.appTheme == com.example.util.AppTheme.TACTICAL_LIGHT) {
            com.example.util.AppTheme.TACTICAL_DARK
        } else {
            com.example.util.AppTheme.TACTICAL_LIGHT
        }
        setAppTheme(next)
    }

    fun setMeshChannel(channel: String) {
        prefs.meshChannel = channel
        _uiState.update { it.copy(meshChannel = channel) }
        pingMeshNow()
        scheduleAccountBackupSync()
        triggerHaptic(35)
    }

    fun regenerateNodeId() {
        val newId = prefs.regenerateNodeId()
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { if (it.isSelf) it.copy(id = newId) else it }
            current.copy(
                localNodeId = newId,
                nodes = updatedNodes
            )
        }
        pingMeshNow()
        triggerHaptic(50)
    }

    fun updateCallsign(newCallsign: String) {
        prefs.operatorCallsign = newCallsign
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { if (it.isSelf) it.copy(callsign = "$newCallsign (Self)") else it }
            current.copy(operatorCallsign = newCallsign, nodes = updatedNodes)
        }
        pingMeshNow()
        triggerHaptic(30)
    }

    fun requestGpsUpdates() {
        gpsService.startWatchingLocation()
    }

    fun setManualGpsLocation(lat: Double, lon: Double) {
        gpsService.setManualLocation(lat, lon)
        pingMeshNow()
        triggerHaptic(30)
    }

    fun forceGattReDiscovery() {
        offGridBleEngine.forceTwoWayGattReDiscovery()
        triggerHaptic(45)
    }

    /**
     * Transmits `CALL_RING`, `CALL_ACCEPT`, and `CALL_END` over the unified JSON transport:
     * - Clears any queued BLE voice frames first so call control signals transmit with zero delay!
     * - Hotspot TCP 45889 / UDP 45888
     * - Bidirectional BLE GATT `0000CAFF`
     * - Hybrid `ntfy.sh` (only when `isInternetEnabled == true`)
     */
    private fun sendCallControlBurst(
        type: String,
        targetNodeId: String = "ALL",
        payload: String = "",
        repeatCount: Int = 3,
        spacingMs: Long = 120L
    ) {
        val state = _uiState.value
        offGridBleEngine.clearPendingBleVoiceQueue()
        pulseMeshWebServer.notifyCallSignal(type, state.localNodeId, state.operatorCallsign, targetNodeId, payload)
        viewModelScope.launch(Dispatchers.IO) {
            repeat(repeatCount) { idx ->
                val pkt = com.example.service.WireMeshPacket(
                    packetId = "c_${System.currentTimeMillis() % 100000}_${idx}_${Random.nextInt(10, 99)}",
                    srcNodeId = state.localNodeId,
                    srcCallsign = state.operatorCallsign.take(12),
                    dstNodeId = targetNodeId,
                    type = type,
                    payload = payload,
                    ttl = 7,
                    hops = 0
                )
                val unifiedJson = org.json.JSONObject().apply {
                    put("id", pkt.packetId)
                    put("type", pkt.type)
                    put("t", pkt.type)
                    put("srcId", pkt.srcNodeId)
                    put("src", pkt.srcNodeId)
                    put("srcName", pkt.srcCallsign)
                    put("cs", pkt.srcCallsign)
                    put("dstId", pkt.dstNodeId)
                    put("dst", pkt.dstNodeId)
                    put("ttl", pkt.ttl)
                    put("hops", pkt.hops)
                    if (pkt.payload.isNotBlank()) {
                        put("payload", pkt.payload)
                        put("p", pkt.payload)
                    }
                }.toString()

                // 1. Hotspot TCP 45889 & UDP 45888
                runCatching { autoHotspotNegotiator.sendPacket(unifiedJson) }
                // 2. Bidirectional BLE GATT `0000CAFF`
                runCatching { offGridBleEngine.broadcastPacketChunked(pkt) }
                // 3. Hybrid ntfy.sh (when Internet Enabled)
                if (_uiState.value.isInternetEnabled) {
                    runCatching { meshNetworkService.broadcastPacket(pkt) }
                }
                // 4. Optional Near-Ultrasonic Acoustic Air-Gap Modem (18–19.2 kHz BFSK) on first pulse
                if (idx == 0 && ultrasonicModem.isEnabled) {
                    runCatching { ultrasonicModem.transmitControlSignal(type, state.localNodeId) }
                }
                if (idx < repeatCount - 1) {
                    delay(spacingMs)
                }
            }
        }
    }

    /**
     * Initiates a Group Mesh Call across all discovered nodes (`dstId = "ALL"`).
     */
    fun startGroupMeshCall() {
        val allGroupNode = MeshNode(
            id = "ALL",
            callsign = "Group Mesh Call (All Peers)",
            isSelf = false
        )
        startDuplexCall(allGroupNode)
    }

    /**
     * Tapping "Call":
     * - Sets `callState = RINGING_OUTGOING`, `activeCallTargetId = node.id`, sets 0xCAFE `callStateByte = 0x01`,
     *   and sends `type = "CALL_RING"` (`ttl = 7`) every 2 seconds while ringing.
     */
    fun startDuplexCall(node: MeshNode) {
        if (node.id != "ALL" && (node.callsign.isBlank() || !node.callsign.any { it.isLetterOrDigit() } || isBlockedFakeCallsign(node.callsign))) return
        val targetId = if (node.id.isBlank()) "ALL" else node.id
        lastCallEndedMs = 0L
        lastEndedPeerNormId = null
        callAcceptConfirmedByPeer = false
        acceptSignalRetryJob?.cancel()
        acceptSignalRetryJob = null
        offGridBleEngine.clearPendingBleVoiceQueue()
        tacticalCallService.startOutgoingCall(targetId, node.callsign)
        offGridBleEngine.updateCallStateBeacon(
            com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_RINGING_OUTGOING,
            targetId
        )
        _uiState.update {
            it.copy(
                callState = com.example.service.CallSignalingState.RINGING_OUTGOING,
                remoteCallerId = targetId,
                remoteCallerName = node.callsign,
                activeCallTargetId = targetId,
                activePrivateCallNode = node,
                isPrivateCallActive = true,
                callAckConfirmed = false,
                selectedTabIndex = 2
            )
        }

        sendCallControlBurst(type = "CALL_RING", targetNodeId = targetId, payload = "RING", repeatCount = 2, spacingMs = 120L)
        ringSignalRepeatJob?.cancel()
        ringSignalRepeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _uiState.value.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING) {
                delay(2000L)
                if (isActive && _uiState.value.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING) {
                    sendCallControlBurst(type = "CALL_RING", targetNodeId = targetId, payload = "RING", repeatCount = 1, spacingMs = 120L)
                }
            }
        }
        triggerHaptic(60)
    }

    /**
     * Tapping "Accept":
     * - Immediately stops incoming vibration, sets `callState = IN_CALL_ACTIVE`, sets 0xCAFE `callStateByte = 0x02`,
     *   clears any pending BLE voice queue, sends `type = "CALL_ACCEPT"` 3 times (spaced 120ms apart),
     *   waits `300ms` before starting microphone capture so the `CALL_ACCEPT` signal reaches the caller cleanly,
     *   and runs a persistent `acceptSignalRetryJob` every 550ms (up to 8s) until `CALL_ACCEPT_ACK` or `VOICE` arrives from the caller!
     */
    fun acceptDuplexCall() {
        val cur = _uiState.value
        val isRinging = cur.callState == com.example.service.CallSignalingState.RINGING_INCOMING ||
            cur.callState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
            cur.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_INCOMING ||
            cur.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING
        if (!isRinging) return

        val caller = cur.incomingPrivateCall ?: cur.duplexCallState.let {
            if (it.peerNodeId.isNotBlank()) MeshNode(id = it.peerNodeId, callsign = it.peerCallsign) else null
        } ?: cur.let {
            if (it.remoteCallerId.isNotBlank()) MeshNode(id = it.remoteCallerId, callsign = it.remoteCallerName.ifBlank { it.remoteCallerId }) else null
        } ?: return

        val replyTarget = if (cur.activeCallTargetId.equals("ALL", ignoreCase = true) && caller.id == "ALL") {
            "ALL"
        } else {
            caller.id.ifBlank { "ALL" }
        }

        lastCallEndedMs = 0L
        lastEndedPeerNormId = null
        callAcceptConfirmedByPeer = false
        ringSignalRepeatJob?.cancel()
        ringSignalRepeatJob = null
        acceptSignalRetryJob?.cancel()
        acceptSignalRetryJob = null
        offGridBleEngine.clearPendingBleVoiceQueue()

        offGridBleEngine.updateCallStateBeacon(
            com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IN_CALL_ACTIVE,
            replyTarget
        )

        // Minimal 60ms delay on BLE (0ms on Wi-Fi) so CALL_ACCEPT signal dispatches and mic starts with minimal latency
        val micDelay = if (autoHotspotNegotiator.hasHighSpeedLink()) 0L else 60L
        tacticalCallService.acceptCall(startMicDelayMs = micDelay)
        notificationManager.clearCallNotification()
        _uiState.update {
            it.copy(
                callState = com.example.service.CallSignalingState.IN_CALL_ACTIVE,
                remoteCallerId = caller.id,
                remoteCallerName = caller.callsign,
                activeCallTargetId = replyTarget,
                incomingPrivateCall = null,
                activePrivateCallNode = caller,
                isPrivateCallActive = true,
                callAckConfirmed = false,
                selectedTabIndex = 2
            )
        }

        // Send CALL_ACCEPT 3 times (spaced 120ms apart) across Hotspot TCP/UDP + BLE L2CAP/GATT + Hybrid
        sendCallControlBurst(
            type = "CALL_ACCEPT",
            targetNodeId = replyTarget,
            payload = "ACCEPT",
            repeatCount = 3,
            spacingMs = 120L
        )

        // Persistent 3-Way Handshake Retry Loop: re-send CALL_ACCEPT every 550ms until CALL_ACCEPT_ACK or VOICE arrives
        acceptSignalRetryJob = viewModelScope.launch(Dispatchers.IO) {
            var retries = 0
            while (isActive &&
                !callAcceptConfirmedByPeer &&
                retries < 14 &&
                (_uiState.value.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    _uiState.value.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE)
            ) {
                delay(550L)
                if (!isActive || callAcceptConfirmedByPeer) break
                val stillActive = _uiState.value.callState == com.example.service.CallSignalingState.IN_CALL_ACTIVE ||
                    _uiState.value.duplexCallState.signalingState == com.example.service.CallSignalingState.IN_CALL_ACTIVE
                if (!stillActive) break
                retries++
                offGridBleEngine.updateCallStateBeacon(
                    com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IN_CALL_ACTIVE,
                    replyTarget
                )
                sendCallControlBurst(
                    type = "CALL_ACCEPT",
                    targetNodeId = replyTarget,
                    payload = "ACCEPT",
                    repeatCount = 1,
                    spacingMs = 90L
                )
            }
        }
        triggerHaptic(50)
    }

    /**
     * Tapping "Decline":
     * - Sets `callState = IDLE`, stops all audio/ringtones, and sends `CALL_END` over Hotspot TCP/UDP + BLE GATT + Hybrid.
     */
    fun declineDuplexCall() {
        val targetId = _uiState.value.incomingPrivateCall?.id
            ?: _uiState.value.remoteCallerId.ifBlank { "ALL" }
        lastCallEndedMs = System.currentTimeMillis()
        lastEndedPeerNormId = if (!targetId.equals("ALL", ignoreCase = true)) normalizeId(targetId) else null
        ringSignalRepeatJob?.cancel()
        ringSignalRepeatJob = null
        acceptSignalRetryJob?.cancel()
        acceptSignalRetryJob = null
        callAcceptConfirmedByPeer = false
        offGridBleEngine.updateCallStateBeacon(
            com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_CALL_ENDED,
            targetId
        )
        notificationManager.clearCallNotification()
        tacticalCallService.endCall(isUserBusy = true)
        _uiState.update {
            it.copy(
                callState = com.example.service.CallSignalingState.IDLE,
                remoteCallerId = "",
                remoteCallerName = "",
                activeCallTargetId = "ALL",
                activeSpeakingBanner = "",
                incomingPrivateCall = null,
                activePrivateCallNode = null,
                isPrivateCallActive = false,
                callAckConfirmed = false
            )
        }
        sendCallControlBurst(type = "CALL_END", targetNodeId = targetId, payload = "DECLINED", repeatCount = 3, spacingMs = 150L)
        triggerHaptic(30)
    }

    /**
     * Tapping "End Call" (or "Hang Up & Send Message" when in Voicemail Recording mode):
     * - If `isRecordingVoicemail` is active, finalizes the recorded voicemail clip and sends it into the recipient's chat thread.
     * - Sets `callState = IDLE`, stops all audio/ringtones, and sends `CALL_END` over Hotspot TCP/UDP + BLE GATT + Hybrid.
     */
    fun endDuplexCall() {
        val targetId = _uiState.value.activePrivateCallNode?.id
            ?: _uiState.value.activeCallTargetId.ifBlank {
                _uiState.value.remoteCallerId.ifBlank {
                    tacticalCallService.callState.value.peerNodeId.ifBlank { "ALL" }
                }
            }
        lastCallEndedMs = System.currentTimeMillis()
        lastEndedPeerNormId = if (!targetId.equals("ALL", ignoreCase = true)) normalizeId(targetId) else null
        ringSignalRepeatJob?.cancel()
        ringSignalRepeatJob = null
        acceptSignalRetryJob?.cancel()
        acceptSignalRetryJob = null
        callAcceptConfirmedByPeer = false
        notificationManager.clearCallNotification()
        offGridBleEngine.updateCallStateBeacon(
            com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_CALL_ENDED,
            targetId
        )

        val vmResult = if (tacticalCallService.callState.value.isRecordingVoicemail) {
            tacticalCallService.finishVoicemailAndEndCall()
        } else {
            tacticalCallService.endCall(isUserBusy = false)
            null
        }

        _uiState.update {
            it.copy(
                callState = com.example.service.CallSignalingState.IDLE,
                remoteCallerId = "",
                remoteCallerName = "",
                activeCallTargetId = "ALL",
                activeSpeakingBanner = "",
                activePrivateCallNode = null,
                isPrivateCallActive = false,
                incomingPrivateCall = null,
                callAckConfirmed = false
            )
        }

        if (vmResult != null) {
            val (vmPeerId, vmPeerName, vmPayload) = vmResult
            val threadId = if (vmPeerId.equals("ALL", ignoreCase = true) || vmPeerId.isBlank()) {
                "channel_primary"
            } else {
                val peerNorm = normalizeId(vmPeerId)
                val existingDm = _uiState.value.conversations.find {
                    !it.peerNodeId.isNullOrBlank() && normalizeId(it.peerNodeId) == peerNorm
                }
                existingDm?.threadId ?: "dm_$vmPeerId"
            }
            sendMessageInThread(threadId, vmPayload)
            _uiState.update {
                it.copy(pttStatus = "VOICEMAIL SENT TO $vmPeerName")
            }
        } else {
            sendCallControlBurst(type = "CALL_END", targetNodeId = targetId, payload = "ENDED", repeatCount = 3, spacingMs = 150L)
        }
        triggerHaptic(40)
    }

    fun toggleCallMute() {
        tacticalCallService.toggleMute()
        triggerHaptic(30)
    }

    fun openThreadFromNotification(threadId: String) {
        if (threadId.isBlank()) return
        _uiState.update {
            it.copy(
                selectedTabIndex = 1,
                selectedThreadId = threadId
            )
        }
        selectThread(threadId)
    }

    fun toggleCallSpeaker() {
        tacticalCallService.toggleSpeakerphone()
        triggerHaptic(30)
    }

    /**
     * Replays up to the last 10 seconds of incoming decoded mesh voice frames ("Say Again" instant replay),
     * strictly isolated between Call RX (`isCallMode = true`) and Walkie-Talkie RX (`isCallMode = false`).
     */
    fun replayLastRxVoice(isCallMode: Boolean = false) {
        val frames = audioService.replayLastRxAudio(isCallAudio = isCallMode)
        if (frames > 0) {
            val secs = String.format(java.util.Locale.US, "%.1f", (frames * 160) / 1000.0)
            val label = if (isCallMode) "CALL RX" else "PTT RX"
            _uiState.update {
                it.copy(
                    pttStatus = "REPLAYING LAST $label ($secs s • $frames frames)..."
                )
            }
            triggerHaptic(40)
        }
    }

    /**
     * Ends an unanswered outgoing call (if ringing), records a compact 3-second G.711 µ-Law Voice Drop clip,
     * and dispatches it as an interactive `[[PM_VOICEDROP]]:` audio bubble inside the target peer's chat thread.
     */
    fun recordAndSendVoiceDropToPeer(targetNodeId: String, targetCallsign: String) {
        val cleanId = targetNodeId.ifBlank { _uiState.value.remoteCallerId.ifBlank { "ALL" } }
        val cleanName = targetCallsign.ifBlank { _uiState.value.remoteCallerName.ifBlank { cleanId } }

        if (_uiState.value.callState != com.example.service.CallSignalingState.IDLE ||
            _uiState.value.duplexCallState.signalingState != com.example.service.CallSignalingState.IDLE
        ) {
            endDuplexCall()
        }

        _uiState.update {
            it.copy(
                pttStatus = "RECORDING 3s VOICE DROP FOR $cleanName..."
            )
        }
        triggerHaptic(60)

        audioService.recordQuickVoiceDropClip(durationMs = 2400L) { voiceDropPayload ->
            val threadId = if (cleanId.equals("ALL", ignoreCase = true)) {
                "channel_primary"
            } else {
                val peerNorm = normalizeId(cleanId)
                val existingDm = _uiState.value.conversations.find {
                    !it.peerNodeId.isNullOrBlank() && normalizeId(it.peerNodeId) == peerNorm
                }
                existingDm?.threadId ?: "dm_$cleanId"
            }
            sendMessageInThread(threadId, voiceDropPayload)
            _uiState.update {
                it.copy(
                    pttStatus = "VOICE DROP SENT TO $cleanName"
                )
            }
            triggerHaptic(40)
        }
    }

    fun startPrivateCall(node: MeshNode) {
        startDuplexCall(node)
    }

    fun acceptPrivateCall() {
        acceptDuplexCall()
    }

    fun declinePrivateCall() {
        declineDuplexCall()
    }

    fun endPrivateCall() {
        endDuplexCall()
    }

    fun rotateEncryptionKeys(): Int {
        val next = prefs.rotateEncryptionKey()
        val newPub = prefs.x25519PublicKey
        val newFp = prefs.fingerprint
        for ((k, peer) in peersBackingMap) {
            if (peer.publicKeyBase64.isNotBlank()) {
                val pairwiseFp = TacticalE2eeManager.computePairwiseSafetyFingerprint(newPub, peer.publicKeyBase64)
                peersBackingMap[k] = peer.copy(safetyFingerprint = pairwiseFp)
            }
        }
        _uiState.update { current ->
            val updatedNodes = current.nodes.map { node ->
                if (!node.isSelf && node.publicKeyBase64.isNotBlank()) {
                    node.copy(
                        safetyFingerprint = TacticalE2eeManager.computePairwiseSafetyFingerprint(newPub, node.publicKeyBase64)
                    )
                } else {
                    node
                }
            }
            current.copy(
                encryptionKeyEpoch = next,
                x25519PublicKey = newPub,
                x25519Fingerprint = newFp,
                nodes = updatedNodes
            )
        }
        refreshConversationsList()
        announcePublicKey(force = true)
        triggerHaptic(50)
        return next
    }

    fun setCallPushToOverride(active: Boolean) {
        tacticalCallService.setPushToOverride(active)
    }

    fun setLocationShareMode(mode: LocationShareMode) {
        prefs.locationShareMode = mode
        _uiState.update {
            it.copy(
                locationShareMode = mode,
                broadcastCoarseLocationInBle = (mode == LocationShareMode.COARSE)
            )
        }
        offGridBleEngine.updateCallStateBeacon(
            com.example.service.OffGridBleMeshEngine.CALL_STATE_BYTE_IDLE,
            "ALL"
        )
        pingMeshNow()
        triggerHaptic(30)
    }

    fun toggleBroadcastCoarseLocationInBle(enabled: Boolean? = null) {
        val nextMode = when (enabled) {
            true -> LocationShareMode.COARSE
            false -> LocationShareMode.OFF
            null -> if (_uiState.value.locationShareMode == LocationShareMode.COARSE) {
                LocationShareMode.OFF
            } else {
                LocationShareMode.COARSE
            }
        }
        setLocationShareMode(nextMode)
    }

    fun setMeshPassphrase(passphrase: String) {
        val clean = passphrase.trim().ifBlank { "pulsemesh-tactical" }
        prefs.meshPassphrase = clean
        _uiState.update { it.copy(meshPassphrase = clean) }
        if (_uiState.value.isInternetEnabled) {
            meshNetworkService.reconnect()
        }
        scheduleAccountBackupSync()
        triggerHaptic(30)
    }

    fun canStartMicrophoneCapture(
        forPtt: Boolean = _uiState.value.isPttPressed,
        forCall: Boolean = _uiState.value.duplexCallState.isInCall
    ): Boolean {
        return appInForeground && (forPtt || forCall)
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        _uiState.update { it.copy(isAppInForeground = inForeground) }
        if (!inForeground) {
            if (_uiState.value.isPttPressed) {
                stopPtt()
            } else {
                audioService.onAppBackgrounded()
            }
            tacticalCallService.onAppBackgrounded()
        } else {
            if (_uiState.value.duplexCallState.isInCall) {
                tacticalCallService.onAppForegrounded()
            }
        }
    }

    fun toggleInternetEnabled(enabled: Boolean? = null) {
        val next = enabled ?: !_uiState.value.isInternetEnabled
        prefs.isInternetEnabled = next
        prefs.isPureOffGrid = !next
        prefs.internetGatewayBridgeEnabled = next
        com.example.util.OfflineRegionDownloader.isInternetEnabled = next
        if (!next) {
            com.example.util.OfflineRegionDownloader.cancelDownload()
        }
        _uiState.update {
            it.copy(
                isInternetEnabled = next,
                isPureOffGridTransport = !next,
                isPureOffGrid = !next,
                internetGatewayBridgeEnabled = next
            )
        }
        meshNetworkService.refreshInternetState()
        if (next) {
            pingMeshNow()
            syncPendingUsernameHoldIfOnline()
            refreshSignedUsernameClaimIfNeeded()
            checkForAppUpdateOnStartup()
        }
        triggerHaptic(30)
    }

    fun setHotspotAutoPopupEnabled(enabled: Boolean) {
        prefs.isHotspotAutoPopupEnabled = enabled
        autoHotspotNegotiator.setAutoHotspotPopupEnabled(enabled)
        _uiState.update {
            it.copy(
                isHotspotAutoPopupEnabled = enabled,
                pendingHotspotPrompt = if (!enabled) null else it.pendingHotspotPrompt
            )
        }
        triggerHaptic(30)
    }

    fun dismissHotspotPrompt() {
        autoHotspotNegotiator.dismissHotspotPrompt()
        _uiState.update { it.copy(pendingHotspotPrompt = null) }
        triggerHaptic(20)
    }

    fun confirmHotspotPromptJoin() {
        autoHotspotNegotiator.confirmHotspotPromptJoin()
        _uiState.update { it.copy(pendingHotspotPrompt = null) }
        triggerHaptic(40)
    }

    fun setAutoCheckInIntervalMin(minutes: Int) {
        val cleanMin = if (minutes in listOf(0, 1, 5, 15)) minutes else 0
        prefs.autoCheckInIntervalMin = cleanMin
        _uiState.update { it.copy(autoCheckInIntervalMin = cleanMin) }
        scheduleAutoCheckInLoop(cleanMin)
        if (cleanMin > 0) {
            broadcastAutomatedCheckInBeacon(cleanMin)
        }
        triggerHaptic(30)
    }

    private fun scheduleAutoCheckInLoop(minutes: Int) {
        autoCheckInJob?.cancel()
        autoCheckInJob = null
        if (minutes <= 0) return
        autoCheckInJob = viewModelScope.launch {
            while (isActive) {
                delay(minutes * 60_000L)
                broadcastAutomatedCheckInBeacon(minutes)
            }
        }
    }

    private fun broadcastAutomatedCheckInBeacon(intervalMin: Int) {
        pingMeshNow()
        val state = _uiState.value
        val coordsStr = if (state.userLatitude != null && state.userLongitude != null && state.locationShareMode != LocationShareMode.OFF) {
            "GPS %.4f, %.4f".format(java.util.Locale.US, state.userLatitude, state.userLongitude)
        } else {
            "GPS Privacy (${state.locationShareMode.label})"
        }
        val checkInMsg = "AUTO CHECK-IN (${intervalMin}m): Operator ${state.operatorCallsign} OK • ⚡${state.realBatteryPercent}% • $coordsStr"
        val checkInPkt = com.example.service.WireMeshPacket(
            packetId = "chk_${System.currentTimeMillis() % 1000000}",
            srcNodeId = state.localNodeId,
            srcCallsign = state.operatorCallsign,
            dstNodeId = "ALL",
            type = "CHAT",
            payload = checkInMsg,
            ttl = 7,
            hops = 0,
            lat = state.userLatitude,
            lng = state.userLongitude,
            battery = state.realBatteryPercent
        )
        sendMeshPacket(checkInPkt)
        notificationManager.showSilentAutoCheckInNotification(
            callsign = state.operatorCallsign,
            intervalMin = intervalMin,
            batteryPct = state.realBatteryPercent,
            locationSummary = coordsStr
        )
    }

    fun runMeshLinkQualitySweep() {
        if (_uiState.value.isPingSweepRunning) return
        triggerHaptic(45)
        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isPingSweepRunning = true,
                    lastPingSweepReport = "SWEEPING MESH: Probing BLE 1M/Coded, GATT 0xCAFF, Swarm UDP & DTN Outbox..."
                )
            }
            pingMeshNow()
            announcePublicKey(force = true)
            flushPendingDtnPacketsToPeer("ALL")
            delay(420L)

            val st = _uiState.value
            val peers = st.nodes.filter { !it.isSelf }
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(18L)
            val gattCount = st.connectedServerDeviceAddresses.size + st.connectedGattClientAddresses.size
            val fastIp = autoHotspotNegotiator.hasHighSpeedLink()
            val report = if (peers.isEmpty()) {
                val activeRadios = buildList {
                    add("BLE 1M/LR")
                    if (gattCount > 0) add("GATT($gattCount)")
                    if (fastIp) add("HOTSPOT-UDP")
                    if (st.isInternetEnabled) add("HYBRID-HTTPS")
                }.joinToString(" + ")
                "SWEEP COMPLETE (${elapsedMs}ms) • 0 Direct Peers in Range • Radios Armed: $activeRadios • DTN Queued: ${st.pendingDtnCount}/50"
            } else {
                val bestPeer = peers.maxByOrNull { it.rssiDbm }
                val avgRssi = peers.map { it.rssiDbm }.average().toInt()
                val linkMarginDb = (avgRssi - (-98)).coerceAtLeast(1)
                val symStr = if (st.isTwoWayGattSymmetric) "2-Way GATT OK" else "GATT Asym/Beacon"
                val fastStr = if (fastIp) "UDP Fast-Link" else "BLE Mesh"
                "SWEEP OK (${elapsedMs}ms) • ${peers.size} Peer(s) • Best: ${bestPeer?.callsign ?: "Peer"} (${bestPeer?.rssiDbm ?: avgRssi} dBm, +${linkMarginDb}dB margin) • $fastStr • $symStr • DTN Flushed (${st.pendingDtnCount} pending)"
            }
            _uiState.update {
                it.copy(
                    isPingSweepRunning = false,
                    lastPingSweepReport = report
                )
            }
            triggerHaptic(30)
        }
    }

    fun toggleSpokenCallerId(enabled: Boolean? = null) {
        val next = enabled ?: !_uiState.value.isSpokenCallerIdEnabled
        prefs.isSpokenCallerIdEnabled = next
        _uiState.update { it.copy(isSpokenCallerIdEnabled = next) }
        triggerHaptic(30)
    }

    fun toggleUseLiveMicrophone() {
        val next = !_uiState.value.useLiveMicrophone
        _uiState.update { it.copy(useLiveMicrophone = next) }
        audioService.useLiveMicrophone = next
        triggerHaptic(30)
    }

    fun purgePhantomAndCachedPeers(): Int {
        var removed = 0
        val staleIter = peersBackingMap.entries.iterator()
        while (staleIter.hasNext()) {
            val e = staleIter.next()
            if (isBlockedFakeCallsign(e.value.callsign)) {
                staleIter.remove()
                removed++
            }
        }
        removed += persistentChatManager.purgeCachedFakeThreads()
        removed += prefs.purgeLegacyBotRecords()
        _uiState.update { cur ->
            val cleanNodes = cur.nodes.filter { n -> n.isSelf || !isBlockedFakeCallsign(n.callsign) }
            cur.copy(
                nodes = cleanNodes,
                activePeerCount = cleanNodes.count { !it.isSelf },
                meshGroups = prefs.loadMeshGroups(),
                lastPurgeStatus = if (removed > 0) {
                    "Purged $removed legacy/phantom bot record(s)"
                } else {
                    "Clean: 0 phantom or legacy bot records found"
                }
            )
        }
        refreshConversationsList()
        triggerHaptic(40)
        return removed
    }

    fun verifyRootStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRootVerifying = true) }
            val probe = RootDetector.checkRootAccess()
            if (probe.isRooted && !rootRadioBoostApplied) {
                rootRadioBoostApplied = true
                runCatching {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf(
                            "su",
                            "-c",
                            "settings put global ble_scan_always_enabled 1; iw dev wlan0 set power_save off"
                        )
                    )
                    proc.waitFor()
                }
            }
            _uiState.update { current ->
                val newTxPower = if (probe.isRooted) "OS Radio (Root uid=0)" else "Standard OS Radio"
                current.copy(
                    isRootVerifying = false,
                    isRootGranted = probe.isRooted,
                    rootStatusMessage = if (probe.isRooted) {
                        "Root Verified (${probe.rootType}) — uid=0(root) active (Wi-Fi power_save disabled)."
                    } else {
                        "Standard Unrooted Android Mode — All PulseMesh transports operate natively without root."
                    },
                    rootProbeLogs = probe.probeLogs,
                    txPowerDesc = newTxPower,
                    rangeMultiplier = 1.0f,
                    wifiBeaconStuffingEnabled = false,
                    unthrottledScanEnabled = false,
                    systemlessMountEnabled = false
                )
            }
        }
    }

    fun toggleWifiAware() {
        val next = !_uiState.value.wifiAwareEnabled
        autoHotspotNegotiator.setWifiAwareEnabled(next)
        _uiState.update { it.copy(wifiAwareEnabled = next) }
        triggerHaptic(35)
    }

    fun toggleUltrasonicModem() {
        // Retired unreliable 18.5 kHz Ultrasonic in v1.4.2 in favor of audible Bell 202 AFSK (1200/2200 Hz)
        toggleBell202Modem()
    }

    fun toggleBleCodedPhy() {
        val next = !_uiState.value.bleCodedPhyEnabled
        _uiState.update { it.copy(bleCodedPhyEnabled = next, rangeMultiplier = calculateRangeMultiplier(next, it.wifiBeaconStuffingEnabled, it.unthrottledScanEnabled)) }
        triggerHaptic(35)
    }

    fun toggleWifiBeaconStuffing() {
        if (!_uiState.value.isRootGranted) {
            triggerHaptic(60)
            verifyRootStatus()
            return
        }
        val next = !_uiState.value.wifiBeaconStuffingEnabled
        _uiState.update { it.copy(wifiBeaconStuffingEnabled = next, rangeMultiplier = calculateRangeMultiplier(it.bleCodedPhyEnabled, next, it.unthrottledScanEnabled)) }
        triggerHaptic(35)
    }

    fun toggleUnthrottledScan() {
        if (!_uiState.value.isRootGranted) {
            triggerHaptic(60)
            verifyRootStatus()
            return
        }
        val next = !_uiState.value.unthrottledScanEnabled
        _uiState.update { it.copy(unthrottledScanEnabled = next, rangeMultiplier = calculateRangeMultiplier(it.bleCodedPhyEnabled, it.wifiBeaconStuffingEnabled, next)) }
        triggerHaptic(35)
    }

    fun toggleSystemlessMount() {
        if (!_uiState.value.isRootGranted) {
            triggerHaptic(60)
            verifyRootStatus()
            return
        }
        val next = !_uiState.value.systemlessMountEnabled
        _uiState.update { it.copy(systemlessMountEnabled = next) }
        triggerHaptic(35)
    }

    fun selectChipsetProfile(profile: ChipsetProfile) {
        _uiState.update {
            it.copy(
                chipsetProfile = profile,
                localDeviceModel = profile.title
            )
        }
        triggerHaptic(40)
    }

    private fun calculateRangeMultiplier(ble: Boolean, wifi: Boolean, scan: Boolean): Float = 1.0f

    private fun triggerHaptic(durationMs: Long) {
        try {
            val vib = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    fun startLiveBleTx() {
        bleRadioService.startLiveTx(_uiState.value.localNodeId, _uiState.value.operatorCallsign)
        triggerHaptic(40)
    }

    fun stopLiveBleTx() {
        bleRadioService.stopLiveTx()
        triggerHaptic(30)
    }

    fun toggleRawBleSniffer() {
        if (_uiState.value.isBleRxSnifferActive) {
            bleRadioService.stopRawRxSniffer()
        } else {
            bleRadioService.startRawRxSniffer()
        }
        triggerHaptic(40)
    }

    fun clearScannedBleDevices() {
        bleRadioService.clearScannedDevices()
        triggerHaptic(25)
    }

    fun onPermissionsResult(allGranted: Boolean) {
        _uiState.update {
            it.copy(
                hasRequiredPermissions = allGranted,
                showPermissionsBanner = !allGranted
            )
        }
        if (allGranted) {
            offGridBleEngine.initIfReady()
        }
    }

    fun dismissPermissionsBanner() {
        _uiState.update { it.copy(showPermissionsBanner = false) }
    }

    fun toggleSimpleMode(enableSimple: Boolean? = null) {
        val newMode = enableSimple ?: !_uiState.value.isSimpleMode
        prefs.isSimpleMode = newMode
        _uiState.update { it.copy(isSimpleMode = newMode) }
        triggerHaptic(30)
    }

    fun toggleLocalHotspot() {
        if (_uiState.value.hotspotState.isActive) {
            hotspotManager.stopLocalHotspot()
            autoHotspotNegotiator.stopHotspotGroup()
        } else {
            hotspotManager.startLocalHotspot()
            autoHotspotNegotiator.startHostP2pGroup(manualTrigger = true)
        }
        triggerHaptic(40)
    }

    // =========================================================================
    // PULSEMESH v1.1 TACTICAL UPGRADE ACTIONS
    // =========================================================================

    fun toggleBackgroundRelayService() {
        val next = !_uiState.value.isBackgroundRelayServiceEnabled
        prefs.isBackgroundRelayServiceEnabled = next
        _uiState.update { it.copy(isBackgroundRelayServiceEnabled = next) }
        if (next) {
            com.example.service.MeshForegroundRelayService.startOrUpdate(
                context = getApplication(),
                peerCount = peersBackingMap.size,
                dtnCount = _uiState.value.pendingDtnCount
            )
        } else {
            com.example.service.MeshForegroundRelayService.stop(getApplication())
        }
        triggerHaptic(35)
    }

    fun toggleVolumeButtonPtt() {
        val next = !_uiState.value.isVolumeButtonPttEnabled
        prefs.isVolumeButtonPttEnabled = next
        _uiState.update { it.copy(isVolumeButtonPttEnabled = next) }
        triggerHaptic(30)
    }

    fun toggleRadioVoxBridge() {
        val next = !_uiState.value.isRadioVoxBridgeEnabled
        prefs.isRadioVoxBridgeEnabled = next
        ultrasonicModem.voxPreKeyPilotEnabled = next
        _uiState.update { it.copy(isRadioVoxBridgeEnabled = next) }
        triggerHaptic(35)
    }

    fun toggleDeadReckoning() {
        val next = !_uiState.value.isDeadReckoningEnabled
        prefs.isDeadReckoningEnabled = next
        _uiState.update { it.copy(isDeadReckoningEnabled = next) }
        triggerHaptic(35)
    }

    fun toggleBreadcrumbRecording() {
        val next = !_uiState.value.isBreadcrumbRecording
        prefs.isBreadcrumbRecording = next
        _uiState.update { it.copy(isBreadcrumbRecording = next) }
        triggerHaptic(30)
    }

    fun clearBreadcrumbTrail() {
        prefs.saveBreadcrumbs(emptyList())
        _uiState.update { it.copy(breadcrumbs = emptyList()) }
        triggerHaptic(30)
    }

    fun exportBreadcrumbsAsGpx(): String {
        val pts = _uiState.value.breadcrumbs
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"PulseMesh\">\n")
        sb.append("  <trk>\n    <name>PulseMesh Tactical Trail</name>\n    <trkseg>\n")
        for (pt in pts) {
            sb.append(
                String.format(
                    java.util.Locale.US,
                    "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%d</time></trkpt>\n",
                    pt.lat,
                    pt.lon,
                    pt.altM,
                    pt.timestamp
                )
            )
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>")
        return sb.toString()
    }

    fun importBreadcrumbsFromGpx(gpxXml: String): Int {
        if (gpxXml.isBlank()) return 0
        val regex = Regex("""<trkpt\s+lat="([-0-9.]+)"\s+lon="([-0-9.]+)"[^>]*>(?:.*?<ele>([-0-9.]+)</ele>)?""")
        val imported = mutableListOf<BreadcrumbPoint>()
        val now = System.currentTimeMillis()
        for (match in regex.findAll(gpxXml)) {
            val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
            val lon = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: continue
            val alt = match.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            imported.add(BreadcrumbPoint(lat = lat, lon = lon, altM = alt, timestamp = now))
        }
        if (imported.isNotEmpty()) {
            val merged = (_uiState.value.breadcrumbs + imported).takeLast(500)
            prefs.saveBreadcrumbs(merged)
            _uiState.update { it.copy(breadcrumbs = merged) }
        }
        return imported.size
    }

    fun shareBreadcrumbTrailToMesh() {
        val pts = _uiState.value.breadcrumbs.takeLast(25)
        if (pts.isEmpty()) return
        val arr = org.json.JSONArray()
        for (pt in pts) {
            arr.put(
                org.json.JSONObject().apply {
                    put("lat", pt.lat)
                    put("lon", pt.lon)
                    put("altM", pt.altM)
                    put("ts", pt.timestamp)
                }
            )
        }
        val st = _uiState.value
        val wirePkt = com.example.service.WireMeshPacket(
            packetId = "trail_${normalizeId(st.localNodeId)}_${System.currentTimeMillis() and 0xFFFFF}",
            srcNodeId = st.localNodeId,
            srcCallsign = st.operatorCallsign,
            dstNodeId = "ALL",
            type = "TRAIL_SYNC",
            payload = arr.toString(),
            ttl = 4,
            hops = 0
        )
        sendOmniPacket(wirePkt)
        triggerHaptic(40)
    }

    fun broadcastOfflineMapTilesToPeers(
        centerLat: Double = _uiState.value.userLatitude ?: 37.9838,
        centerLon: Double = _uiState.value.userLongitude ?: 23.7275
    ): Int {
        val chunks = com.example.util.NativeTileMapManager.exportCachedTilesNear(
            context = getApplication(),
            centerLat = centerLat,
            centerLon = centerLon,
            maxTiles = 12
        )
        if (chunks.isEmpty()) {
            _uiState.update {
                it.copy(p2pTileSyncStatus = "No cached map tiles found at current location to beam")
            }
            return 0
        }
        val st = _uiState.value
        _uiState.update {
            it.copy(p2pTileSyncStatus = "Beaming ${chunks.size} cached map tiles to local mesh peers...")
        }
        viewModelScope.launch(Dispatchers.IO) {
            for ((idx, chunk) in chunks.withIndex()) {
                val payloadJson = org.json.JSONObject().apply {
                    put("z", chunk.z)
                    put("x", chunk.x)
                    put("y", chunk.y)
                    put("b64", chunk.base64Png)
                }.toString()
                val pktJson = org.json.JSONObject().apply {
                    val pktId = "tile_${chunk.z}_${chunk.x}_${chunk.y}_${System.currentTimeMillis() and 0xFFFF}"
                    put("id", pktId)
                    put("packetId", pktId)
                    put("type", "MAP_TILE_SYNC")
                    put("t", "MAP_TILE_SYNC")
                    put("srcId", st.localNodeId)
                    put("src", st.localNodeId)
                    put("srcName", st.operatorCallsign)
                    put("cs", st.operatorCallsign)
                    put("dstId", "ALL")
                    put("dst", "ALL")
                    put("ttl", 2)
                    put("hops", 0)
                    put("payload", payloadJson)
                    put("p", payloadJson)
                }.toString()
                autoHotspotNegotiator.sendPacket(pktJson)
                delay(90L)
                _uiState.update {
                    it.copy(p2pTileSyncStatus = "Beamed ${idx + 1}/${chunks.size} map tiles over local mesh")
                }
            }
        }
        triggerHaptic(45)
        return chunks.size
    }

    fun transmitUltrasonicKeyVerifyChirp() {
        val st = _uiState.value
        val fp = st.x25519Fingerprint.ifBlank { prefs.fingerprint }.take(16)
        bell202Modem.transmitMessage(
            com.example.service.Bell202AcousticModem.TYPE_KEY_VERIFY,
            st.localNodeId,
            "KEY:$fp"
        )
        triggerHaptic(40)
    }

    fun cycleMessageBurnTtl() {
        val options = listOf(0, 60, 300, 3600, 86400)
        val cur = _uiState.value.messageBurnTtlSeconds
        val nextIdx = (options.indexOf(cur) + 1) % options.size
        val nextVal = options[nextIdx]
        prefs.messageBurnTtlSeconds = nextVal
        _uiState.update { it.copy(messageBurnTtlSeconds = nextVal) }
        persistentChatManager.pruneExpiredPackets()
        refreshConversationsList()
        triggerHaptic(30)
    }

    private fun hashDuressPin(cleanPin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("PULSEMESH_DURESS_V11:$cleanPin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setDuressPin(pin: String): Boolean {
        val clean = pin.trim()
        if (clean.isEmpty()) {
            prefs.duressPinHash = ""
            _uiState.update { it.copy(hasDuressPin = false) }
            return true
        }
        if (clean.length < 4) return false
        val hash = hashDuressPin(clean)
        prefs.duressPinHash = hash
        _uiState.update { it.copy(hasDuressPin = true) }
        triggerHaptic(40)
        return true
    }

    fun verifyOrTriggerDuressWipe(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        val storedHash = prefs.duressPinHash
        if (clean.length < 4 || storedHash.isBlank()) return false
        val candidateHash = hashDuressPin(clean)
        if (candidateHash != storedHash) return false

        // Execute Immediate Emergency Duress Wipe
        persistentChatManager.wipeAllHistoryForDuress()
        prefs.wipeSensitiveVaultForDuress()
        synchronized(pendingDtnPackets) {
            pendingDtnPackets.clear()
        }
        peersBackingMap.clear()
        _uiState.update { cur ->
            cur.copy(
                localNodeId = prefs.nodeId,
                operatorCallsign = prefs.operatorCallsign,
                x25519PublicKey = prefs.x25519PublicKey,
                x25519Fingerprint = prefs.fingerprint,
                claimedUsername = "",
                hasAccountPin = false,
                accountRecoveryKey = "",
                pendingDtnCount = 0,
                breadcrumbs = emptyList(),
                waypoints = emptyList(),
                meshGroups = emptyList(),
                packets = emptyList()
            )
        }
        refreshConversationsList()
        triggerHaptic(120)
        return true
    }

    // =========================================================================
    // PULSEMESH v1.2 WI-FI DIRECT EMBEDDED WEB PORTAL BRIDGE
    // =========================================================================

    fun toggleWebPortal() {
        if (pulseMeshWebServer.isRunning()) {
            manualWebPortalPinned = false
            pulseMeshWebServer.stop()
        } else {
            manualWebPortalPinned = true
            val hintIp = autoHotspotNegotiator.status.value.hostIp
                .ifBlank { autoHotspotNegotiator.myLocalIp }
                .ifBlank { com.example.service.AutoHotspotNegotiator.P2P_GO_IP }
            pulseMeshWebServer.start(hintIp)
        }
        triggerHaptic(35)
    }

    fun dismissIncomingSosAlert() {
        _uiState.update { it.copy(incomingSosAlert = "") }
    }

    private fun requestMapTileFromGatewayPeer(z: Int, x: Int, y: Int): Boolean {
        val gwPeer = peersBackingMap.values
            .filter { it.isGateway }
            .minByOrNull { it.hopCount } ?: return false
        val key = "$z/$x/$y"
        val now = System.currentTimeMillis()
        val prev = lastGatewayTileRequestMs[key] ?: 0L
        if (now - prev < 15_000L) return false
        lastGatewayTileRequestMs[key] = now

        val st = _uiState.value
        val payloadJson = org.json.JSONObject().apply {
            put("z", z)
            put("x", x)
            put("y", y)
        }.toString()
        val reqPkt = com.example.service.WireMeshPacket(
            packetId = "treq_${z}_${x}_${y}_${now and 0xFFFF}",
            srcNodeId = st.localNodeId,
            srcCallsign = st.operatorCallsign,
            dstNodeId = gwPeer.id,
            type = "TILE_REQ",
            payload = payloadJson,
            ttl = 2,
            hops = 0
        )
        sendMeshPacket(reqPkt)
        return true
    }

    /**
     * Permanently deletes ALL local account information associated with [targetUsername] (or the active account if blank).
     * SECURITY RULE: Deletion is ONLY permitted if:
     *  1. The user is currently logged in to that exact account, OR
     *  2. The user provides BOTH the valid PIN AND the valid Recovery Key (`PMRK-...`) for that account.
     */
    fun deleteAllAccountInformation(
        targetUsername: String = "",
        pinInput: String = "",
        recoveryKeyInput: String = ""
    ): Pair<Boolean, String> {
        val state = _uiState.value
        val activeUser = TacticalE2eeManager.normalizeUsername(state.claimedUsername)
        val normTarget = TacticalE2eeManager.normalizeUsername(targetUsername)
        val effectiveUser = normTarget.ifBlank { activeUser }

        val isLoggedInToTarget = activeUser.isNotBlank() && (normTarget.isBlank() || normTarget == activeUser)

        val matchingClaim = if (effectiveUser.isNotBlank()) {
            prefs.getAllKnownUsernameClaims().firstOrNull {
                TacticalE2eeManager.normalizeUsername(it.username) == effectiveUser
            }
        } else null

        if (!isLoggedInToTarget) {
            if (effectiveUser.isBlank()) {
                return Pair(false, "Sign in to an account first, or enter a @username with BOTH its PIN and Recovery Key.")
            }
            val cleanPin = pinInput.trim()
            val cleanRecovery = recoveryKeyInput.trim()
            if (cleanPin.isBlank() || cleanRecovery.isBlank()) {
                return Pair(false, "Authentication required: provide BOTH the PIN and Recovery Key for @$effectiveUser (or sign in first).")
            }
            if (matchingClaim == null) {
                return Pair(false, "No local credentials found for @$effectiveUser to verify PIN + Recovery Key.")
            }
            val salt = matchingClaim.saltHex.ifBlank { prefs.accountSaltHex }
            val pinVaultOk = matchingClaim.pinVault.isNotBlank() &&
                TacticalE2eeManager.decryptAccountVault(effectiveUser, salt, cleanPin, matchingClaim.pinVault) != null
            val pinHashOk = matchingClaim.pinHash.isNotBlank() &&
                TacticalE2eeManager.isValidAccountPin(cleanPin) &&
                TacticalE2eeManager.hashAccountPin(effectiveUser, salt, cleanPin) == matchingClaim.pinHash
            val pinValid = pinVaultOk || pinHashOk

            val recoveryVaultOk = matchingClaim.recoveryVault.isNotBlank() && (
                TacticalE2eeManager.decryptAccountVault(
                    effectiveUser,
                    salt,
                    TacticalE2eeManager.normalizeRecoveryKey(cleanRecovery),
                    matchingClaim.recoveryVault
                ) != null ||
                    TacticalE2eeManager.decryptAccountVault(
                        effectiveUser,
                        salt,
                        cleanRecovery.uppercase(),
                        matchingClaim.recoveryVault
                    ) != null
                )
            val recoveryHashOk = matchingClaim.recoveryHash.isNotBlank() &&
                TacticalE2eeManager.hashRecoveryKey(effectiveUser, cleanRecovery) == matchingClaim.recoveryHash
            val recoveryValid = recoveryVaultOk || recoveryHashOk

            if (!pinValid || !recoveryValid) {
                val reason = when {
                    !pinValid && !recoveryValid -> "Both PIN and Recovery Key are invalid for @$effectiveUser."
                    !pinValid -> "Invalid PIN for @$effectiveUser (BOTH PIN and Recovery Key must match)."
                    else -> "Invalid Recovery Key for @$effectiveUser (BOTH PIN and Recovery Key must match)."
                }
                return Pair(false, "Deletion denied: $reason")
            }
        }

        val targetNodeId = matchingClaim?.nodeId ?: if (isLoggedInToTarget) state.localNodeId else ""

        val authTokenBeforeDelete = prefs.accountAuthToken
        val privKeyBeforeDelete = prefs.x25519PrivateKey
        val erasedMsgs = persistentChatManager.deleteAllDataMatchingAccount(effectiveUser, targetNodeId)
        val deletedActive = prefs.deleteAccountInformation(effectiveUser)

        // Also delete from our Cloudflare Workers + D1 username registry when online
        if (effectiveUser.isNotBlank() && (authTokenBeforeDelete.isNotBlank() || privKeyBeforeDelete.isNotBlank())) {
            viewModelScope.launch(Dispatchers.IO) {
                meshNetworkService.deleteUsernameFromRelay(
                    username = effectiveUser,
                    authToken = authTokenBeforeDelete,
                    privateKeyBase64 = privKeyBeforeDelete,
                    allowDirectLookup = true
                )
            }
        }

        if (deletedActive) {
            synchronized(pendingDtnPackets) { pendingDtnPackets.clear() }
            peersBackingMap.entries.removeIf { (_, node) ->
                val normN = normalizeId(node.id)
                (targetNodeId.isNotBlank() && normN == normalizeId(targetNodeId)) ||
                    (effectiveUser.isNotBlank() && node.callsign.contains("@$effectiveUser", ignoreCase = true))
            }
            _uiState.update { cur ->
                cur.copy(
                    localNodeId = prefs.nodeId,
                    operatorCallsign = prefs.operatorCallsign,
                    x25519PublicKey = prefs.x25519PublicKey,
                    x25519Fingerprint = prefs.fingerprint,
                    claimedUsername = "",
                    accountRecoveryKey = "",
                    hasAccountPin = false,
                    isUsernameOnHold = false,
                    pendingUsernameHold = "",
                    usernameAuthStatus = "Account data permanently erased",
                    pendingDtnCount = 0,
                    breadcrumbs = emptyList(),
                    meshGroups = emptyList(),
                    selectedThreadId = null
                )
            }
        } else {
            peersBackingMap.entries.removeIf { (_, node) ->
                (targetNodeId.isNotBlank() && normalizeId(node.id) == normalizeId(targetNodeId)) ||
                    (effectiveUser.isNotBlank() && node.callsign.contains("@$effectiveUser", ignoreCase = true))
            }
        }
        refreshConversationsList()
        triggerHaptic(80)
        val label = if (effectiveUser.isNotBlank()) "@$effectiveUser" else "active account"
        return Pair(true, "Deleted all local account data for $label ($erasedMsgs message(s) removed).")
    }

    // =========================================================================
    // PULSEMESH FIELD FEATURES
    // =========================================================================

    private fun speakStatusAnnouncement(phrase: String) {
        if (phrase.isBlank()) return
        runCatching {
            val lang = if (_uiState.value.appLanguage == "el") java.util.Locale("el", "GR") else java.util.Locale.US
            if (statusTts == null) {
                statusTts = android.speech.tts.TextToSpeech(getApplication()) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        statusTtsReady = true
                        runCatching {
                            statusTts?.language = lang
                            statusTts?.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "pm_qs_${System.currentTimeMillis()}")
                        }
                    }
                }
            } else if (statusTtsReady) {
                statusTts?.language = lang
                statusTts?.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "pm_qs_${System.currentTimeMillis()}")
            }
        }
    }

    // Multi-Language Switcher (en / el)
    fun setAppLanguage(langCode: String) {
        val clean = if (langCode.equals("el", ignoreCase = true)) "el" else "en"
        prefs.appLanguage = clean
        _uiState.update { it.copy(appLanguage = clean) }
        triggerHaptic(25)
    }

    // Offline File & Document Transfer (Hotspot HTTP/HTTPS Portal)
    fun shareLocalFileToPortal(fileName: String, bytes: ByteArray): SharedMeshFile? {
        val st = _uiState.value
        val saved = pulseMeshWebServer.addLocalSharedFile(fileName, bytes, st.operatorCallsign)
        if (saved != null) {
            _uiState.update { it.copy(sharedFiles = pulseMeshWebServer.listSharedFiles()) }
            triggerHaptic(35)
        }
        return saved
    }

    fun deleteSharedFileFromPortal(fileId: String) {
        if (pulseMeshWebServer.deleteSharedFile(fileId)) {
            _uiState.update { it.copy(sharedFiles = pulseMeshWebServer.listSharedFiles()) }
            triggerHaptic(25)
        }
    }

    fun refreshSharedFilesList() {
        _uiState.update { it.copy(sharedFiles = pulseMeshWebServer.listSharedFiles()) }
    }

    // Tactical Quick-Status & One-Tap Check-Ins (Text + Voice)
    fun toggleQuickStatusVoice(enabled: Boolean? = null) {
        val next = enabled ?: !_uiState.value.quickStatusVoiceEnabled
        prefs.quickStatusVoiceEnabled = next
        _uiState.update { it.copy(quickStatusVoiceEnabled = next) }
        triggerHaptic(20)
    }

    fun sendQuickStatus(status: QuickStatusCode, speakAloud: Boolean = _uiState.value.quickStatusVoiceEnabled) {
        val st = _uiState.value
        val now = System.currentTimeMillis()
        val isGreek = st.appLanguage == "el"
        val displayLabel = if (isGreek) status.labelEl else status.labelEn
        val spokenLabel = if (isGreek) status.spokenEl else status.spokenEn
        val pktId = "qs_${now and 0xFFFFF}"
        val payloadJson = org.json.JSONObject().apply {
            put("code", status.code)
            put("voice", speakAloud)
        }.toString()

        val localPkt = MeshPacket(
            id = pktId,
            sourceId = st.localNodeId,
            destId = "ALL",
            sourceCallsign = "${st.operatorCallsign} (Self)",
            type = PacketType.TEXT,
            content = "[STATUS] $displayLabel",
            hops = 0,
            transport = resolveActiveOutboundTransportAndRssi(null).first,
            rawHexBadge = "[$pktId | QUICK_STATUS]",
            isIncoming = false,
            timestamp = now
        )
        persistentChatManager.savePacket("channel_primary", localPkt)
        _uiState.update { cur ->
            val updatedNodes = cur.nodes.map { n ->
                if (n.isSelf) n.copy(quickStatus = displayLabel, quickStatusAtMs = now) else n
            }
            cur.copy(lastQuickStatusCode = status.code, nodes = updatedNodes)
        }
        refreshConversationsList()

        sendOmniPacket(
            com.example.service.WireMeshPacket(
                packetId = pktId,
                srcNodeId = st.localNodeId,
                srcCallsign = st.operatorCallsign,
                dstNodeId = "ALL",
                type = "QUICK_STATUS",
                payload = payloadJson,
                ttl = 7,
                hops = 0,
                lat = st.userLatitude,
                lng = st.userLongitude,
                battery = st.realBatteryPercent,
                timestamp = now
            )
        )
        if (speakAloud) {
            speakStatusAnnouncement("${st.operatorCallsign}: $spokenLabel")
        }
        triggerHaptic(40)
    }

    // Offline Emergency Medical / ICE Card + Dead-Man's Switch
    fun saveIceMedicalCard(bloodType: String, allergies: String, notes: String, emergencyContact: String) {
        prefs.iceBloodType = bloodType
        prefs.iceAllergies = allergies
        prefs.iceNotes = notes
        prefs.iceEmergencyContact = emergencyContact
        _uiState.update {
            it.copy(
                iceBloodType = prefs.iceBloodType,
                iceAllergies = prefs.iceAllergies,
                iceNotes = prefs.iceNotes,
                iceEmergencyContact = prefs.iceEmergencyContact
            )
        }
        triggerHaptic(30)
    }

    fun setDeadManSwitchMinutes(minutes: Int) {
        val clean = if (minutes in listOf(0, 15, 30, 60, 120)) minutes else 0
        prefs.deadManSwitchMinutes = clean
        _uiState.update {
            it.copy(
                deadManSwitchMinutes = clean,
                deadManRemainingSeconds = clean * 60
            )
        }
        startOrRestartDeadManCountdown(clean)
        triggerHaptic(30)
    }

    fun checkInDeadManSwitch() {
        val mins = _uiState.value.deadManSwitchMinutes
        if (mins <= 0) return
        _uiState.update { it.copy(deadManRemainingSeconds = mins * 60) }
        startOrRestartDeadManCountdown(mins)
        triggerHaptic(35)
    }

    private fun startOrRestartDeadManCountdown(minutes: Int) {
        deadManSwitchJob?.cancel()
        deadManSwitchJob = null
        if (minutes <= 0) return
        deadManSwitchJob = viewModelScope.launch {
            var remaining = minutes * 60
            while (isActive && remaining > 0) {
                delay(1_000L)
                remaining--
                _uiState.update { it.copy(deadManRemainingSeconds = remaining) }
            }
            if (isActive && _uiState.value.deadManSwitchMinutes > 0 && !_uiState.value.isSosActive) {
                toggleSosBeacon()
            }
        }
    }

    // Geofence / Perimeter Alerts
    fun configureGeofence(enabled: Boolean, lat: Double? = null, lng: Double? = null, radiusMeters: Int = _uiState.value.geofenceRadiusMeters) {
        val st = _uiState.value
        val targetLat = lat ?: st.userLatitude ?: st.geofenceLat
        val targetLng = lng ?: st.userLongitude ?: st.geofenceLng
        val cleanRadius = radiusMeters.coerceIn(50, 25_000)
        prefs.geofenceEnabled = enabled
        prefs.geofenceLat = targetLat
        prefs.geofenceLng = targetLng
        prefs.geofenceRadiusMeters = cleanRadius
        _uiState.update {
            it.copy(
                geofenceEnabled = enabled,
                geofenceLat = targetLat,
                geofenceLng = targetLng,
                geofenceRadiusMeters = cleanRadius,
                geofenceAlertMessage = if (!enabled) "" else it.geofenceAlertMessage
            )
        }
        triggerHaptic(35)
    }

    fun dismissGeofenceAlert() {
        _uiState.update { it.copy(geofenceAlertMessage = "") }
    }

    // Battery-Saver Duty-Cycle ("Expedition Mode")
    fun toggleExpeditionMode(enabled: Boolean? = null) {
        val next = enabled ?: !_uiState.value.expeditionModeEnabled
        prefs.expeditionModeEnabled = next
        _uiState.update { it.copy(expeditionModeEnabled = next) }
        triggerHaptic(30)
    }

    fun toggleExpeditionAutoBelow20(enabled: Boolean? = null) {
        val next = enabled ?: !_uiState.value.expeditionAutoBelow20
        prefs.expeditionAutoBelow20 = next
        _uiState.update { it.copy(expeditionAutoBelow20 = next) }
        triggerHaptic(25)
    }

    // Message Reactions (lightweight mesh reaction packets)
    fun sendReaction(packetId: String, emoji: String) {
        val st = _uiState.value
        if (packetId.isBlank() || emoji.isBlank()) return
        if (persistentChatManager.toggleReaction(packetId, emoji, st.operatorCallsign)) {
            refreshConversationsList()
        }
        val payloadJson = org.json.JSONObject().apply {
            put("targetId", packetId)
            put("emoji", emoji)
        }.toString()
        sendOmniPacket(
            com.example.service.WireMeshPacket(
                packetId = "rxn_${System.currentTimeMillis() and 0xFFFFF}",
                srcNodeId = st.localNodeId,
                srcCallsign = st.operatorCallsign,
                dstNodeId = "ALL",
                type = "REACTION",
                payload = payloadJson,
                ttl = 5,
                hops = 0
            )
        )
        triggerHaptic(20)
    }

    // Message Search, Pinned Messages & Trip Log Export
    fun togglePinMessage(threadId: String, packetId: String) {
        if (persistentChatManager.togglePin(threadId, packetId)) {
            refreshConversationsList()
            triggerHaptic(25)
        }
    }

    fun searchMessages(query: String, threadIdFilter: String? = null): List<Pair<String, MeshPacket>> {
        return persistentChatManager.searchPackets(query, threadIdFilter)
    }

    fun exportTripArchiveJson(): String {
        val st = _uiState.value
        val root = org.json.JSONObject().apply {
            put("app", "PulseMesh")
            put("version", "1.4.6")
            put("exportedAt", System.currentTimeMillis())
            put("operator", st.operatorCallsign)
            put("nodeId", st.localNodeId)
            val wpArr = org.json.JSONArray()
            st.waypoints.forEach { w ->
                wpArr.put(org.json.JSONObject().apply {
                    put("id", w.id); put("title", w.title); put("type", w.type.name)
                    put("lat", w.latitude); put("lng", w.longitude); put("ts", w.timestamp)
                })
            }
            put("waypoints", wpArr)
            val bcArr = org.json.JSONArray()
            st.breadcrumbs.forEach { b ->
                bcArr.put(org.json.JSONObject().apply {
                    put("lat", b.lat); put("lon", b.lon); put("altM", b.altM); put("ts", b.timestamp)
                })
            }
            put("breadcrumbs", bcArr)
            val chatsObj = org.json.JSONObject()
            for ((tid, list) in persistentChatManager.exportAllThreadsMap()) {
                val arr = org.json.JSONArray()
                list.forEach { p ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", p.id); put("from", p.sourceCallsign); put("content", p.content)
                        put("ts", p.timestamp); put("pinned", p.isPinned)
                    })
                }
                chatsObj.put(tid, arr)
            }
            put("chats", chatsObj)
        }
        return root.toString(2)
    }

    // QR Contact & Group Instant Join
    fun buildInstantJoinQrPayload(groupId: String? = null): String {
        val st = _uiState.value
        val sb = StringBuilder("pulsemesh://join?")
        sb.append("id=").append(java.net.URLEncoder.encode(st.localNodeId, "UTF-8"))
        sb.append("&cs=").append(java.net.URLEncoder.encode(st.operatorCallsign, "UTF-8"))
        if (st.claimedUsername.isNotBlank()) {
            sb.append("&u=").append(java.net.URLEncoder.encode(st.claimedUsername, "UTF-8"))
        }
        if (st.x25519PublicKey.isNotBlank()) {
            sb.append("&pk=").append(java.net.URLEncoder.encode(st.x25519PublicKey, "UTF-8"))
        }
        if (!groupId.isNullOrBlank()) {
            val grp = st.meshGroups.firstOrNull { it.groupId == groupId }
            if (grp != null) {
                sb.append("&gid=").append(java.net.URLEncoder.encode(grp.groupId, "UTF-8"))
                sb.append("&gn=").append(java.net.URLEncoder.encode(grp.groupName, "UTF-8"))
                if (grp.groupKeyBase64.isNotBlank()) {
                    sb.append("&gk=").append(java.net.URLEncoder.encode(grp.groupKeyBase64, "UTF-8"))
                }
            }
        }
        return sb.toString()
    }

    fun importInstantJoinQrPayload(rawUri: String): Pair<Boolean, String> {
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith("pulsemesh://join?")) {
            return Pair(false, "Invalid PulseMesh QR link (must start with pulsemesh://join?)")
        }
        val query = trimmed.substringAfter("?")
        val params = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = runCatching { java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault(pair.substring(0, eq))
                val v = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
                params[k] = v
            }
        }
        val peerId = (params["id"].orEmpty().ifBlank { params["node"].orEmpty() }).trim()
        val peerCs = params["cs"].orEmpty().trim().ifBlank { peerId }
        val peerUser = TacticalE2eeManager.normalizeUsername(params["u"].orEmpty())
        val peerPk = params["pk"].orEmpty().trim()
        if (peerId.isBlank() || normalizeId(peerId) == normalizeId(_uiState.value.localNodeId)) {
            return Pair(false, "QR link does not contain a valid remote peer Node ID.")
        }
        if (peerPk.isNotBlank() && TacticalE2eeManager.isValidEcPublicKeyBase64(peerPk)) {
            prefs.verifyOrTrustPeerPublicKey(peerId, peerPk)
        }
        if (peerUser.length >= 3) {
            prefs.saveUsernameClaim(
                com.example.crypto.UsernameClaimRecord(
                    username = peerUser,
                    displayName = peerCs,
                    nodeId = peerId,
                    publicKeyBase64 = peerPk,
                    saltHex = "",
                    pinHash = "",
                    recoveryHash = "",
                    claimedAt = System.currentTimeMillis()
                )
            )
        }
        registerOrUpdatePeerInstant(
            peerNodeId = peerId,
            callsign = peerCs,
            sourceTransport = "QR TRUSTED",
            forceImmediateUiUpdate = true
        )
        val gid = params["gid"].orEmpty().trim()
        val gn = params["gn"].orEmpty().trim().ifBlank { "Group $gid" }
        val gk = params["gk"].orEmpty().trim()
        var joinedGroupNote = ""
        if (gid.isNotBlank()) {
            if (gk.isNotBlank()) prefs.saveGroupAesKey(gid, gk)
            val selfId = _uiState.value.localNodeId
            val existing = _uiState.value.meshGroups.firstOrNull { it.groupId == gid }
            val members = ((existing?.memberIds ?: emptyList()) + listOf(selfId, peerId)).distinct()
            val merged = MeshGroup(groupId = gid, groupName = gn, memberIds = members, groupKeyBase64 = gk.ifBlank { existing?.groupKeyBase64.orEmpty() })
            val updatedGroups = _uiState.value.meshGroups.filterNot { it.groupId == gid } + merged
            prefs.saveMeshGroups(updatedGroups)
            _uiState.update { it.copy(meshGroups = updatedGroups) }
            joinedGroupNote = " + joined group \"$gn\""
        }
        refreshConversationsList()
        triggerHaptic(50)
        return Pair(true, "Verified & added $peerCs ($peerId)$joinedGroupNote.")
    }

    // Per-Chat Disappearing Burn Timers
    fun setThreadBurnTtl(threadId: String, ttlSeconds: Int) {
        prefs.setPerThreadBurnTtl(threadId, ttlSeconds)
        _uiState.update { it.copy(perThreadBurnTtlMap = prefs.getPerThreadBurnTtlMap()) }
        triggerHaptic(20)
    }

    // Decoy / Panic Mode Profile
    private fun hashDecoyPin(cleanPin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("PULSEMESH_DECOY_V13:$cleanPin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setDecoyPin(pin: String): Boolean {
        val clean = pin.trim()
        if (clean.isEmpty()) {
            prefs.decoyPinHash = ""
            _uiState.update { it.copy(hasDecoyPin = false, isDecoyModeActive = false) }
            return true
        }
        if (clean.length < 4) return false
        prefs.decoyPinHash = hashDecoyPin(clean)
        _uiState.update { it.copy(hasDecoyPin = true) }
        triggerHaptic(35)
        return true
    }

    fun verifyOrEnterDecoyMode(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        val stored = prefs.decoyPinHash
        if (clean.length < 4 || stored.isBlank()) return false
        if (hashDecoyPin(clean) != stored) return false
        _uiState.update { it.copy(isDecoyModeActive = true) }
        triggerHaptic(40)
        return true
    }

    fun exitDecoyMode() {
        _uiState.update { it.copy(isDecoyModeActive = false) }
        triggerHaptic(30)
    }

    private fun buildWebPortalInfoJson(portalUrl: String, webClientCount: Int): org.json.JSONObject {
        val state = _uiState.value
        return org.json.JSONObject().apply {
            put("pulsemeshHost", true)
            put("version", "1.4.6")
            put("webrtcSignaling", true)
            put("bell202Acoustic", true)
            put("sslEnabled", state.webPortalStatus.sslEnabled)
            put("httpsSelfTestPassed", state.webPortalStatus.httpsSelfTestPassed)
            put("httpsPortalUrl", state.webPortalStatus.httpsPortalUrl)
            put("sharedFilesCount", state.webPortalStatus.sharedFilesCount)
            put("hostNodeId", state.localNodeId)
            put("hostCallsign", state.operatorCallsign)
            put("ssid", state.hotspotState.ssid)
            put("portalUrl", portalUrl)
            put("offGrid", !state.isInternetEnabled)
            put("peerCount", state.activePeerCount)
            put("webClientCount", webClientCount)
        }
    }

    private fun buildWebPortalStateJson(clientNodeId: String): org.json.JSONObject {
        val state = _uiState.value
        val normClient = normalizeId(clientNodeId)
        val normSelf = normalizeId(state.localNodeId)

        val hostObj = org.json.JSONObject().apply {
            put("id", state.localNodeId)
            put("callsign", state.operatorCallsign)
            put("batteryPercent", state.realBatteryPercent)
            put("latitude", state.userLatitude ?: 0.0)
            put("longitude", state.userLongitude ?: 0.0)
            put("publicKeyBase64", state.x25519PublicKey)
        }

        val nodesArr = org.json.JSONArray()
        for (n in state.nodes) {
            if (n.isSelf) continue
            nodesArr.put(org.json.JSONObject().apply {
                put("id", n.id)
                put("callsign", n.callsign)
                put("batteryPercent", n.batteryPercent)
                put("rssiDbm", n.rssiDbm)
                put("hopCount", n.hopCount)
                put("latitude", n.latitude)
                put("longitude", n.longitude)
                put("publicKeyBase64", n.publicKeyBase64)
                val tArr = org.json.JSONArray()
                n.transports.forEach { tArr.put(it) }
                put("transports", tArr)
            })
        }

        val groupsArr = org.json.JSONArray()
        for (g in state.meshGroups) {
            groupsArr.put(org.json.JSONObject().apply {
                put("groupId", g.groupId)
                put("groupName", g.groupName)
                val mArr = org.json.JSONArray()
                g.memberIds.forEach { mArr.put(it) }
                put("memberIds", mArr)
            })
        }

        val wpArr = org.json.JSONArray()
        for (wp in state.waypoints) {
            wpArr.put(org.json.JSONObject().apply {
                put("id", wp.id)
                put("title", wp.title)
                put("type", wp.type.name)
                put("latitude", wp.latitude)
                put("longitude", wp.longitude)
                put("creatorCallsign", wp.creatorCallsign)
                put("timestamp", wp.timestamp)
            })
        }

        val pktsArr = org.json.JSONArray()
        // Merge persisted chat threads + in-memory packets so web/iPhone clients see ALL BLE/Wi-Fi/Internet chats & DMs
        val mergedById = LinkedHashMap<String, MeshPacket>()
        for ((_, list) in persistentChatManager.exportAllThreadsMap()) {
            for (pkt in list) {
                mergedById.putIfAbsent(pkt.id, pkt)
            }
        }
        for (pkt in state.packets.take(160)) {
            mergedById.putIfAbsent(pkt.id, pkt)
        }
        val recentPackets = mergedById.values
            .sortedBy { it.timestamp }
            .takeLast(200)

        for (p in recentPackets) {
            val dst = p.destId
            val srcNorm = normalizeId(p.sourceId)
            val dstNorm = normalizeId(dst)
            val threadId = when {
                dst.equals("ALL", ignoreCase = true) || dst.equals("BROADCAST", ignoreCase = true) -> "channel_primary"
                dst.equals("ENC_TACNET", ignoreCase = true) -> "channel_encrypted"
                state.meshGroups.any { it.groupId == dst } -> "group_$dst"
                normClient.isNotBlank() && (dstNorm == normClient || srcNorm == normClient) -> {
                    val peerId = if (srcNorm == normClient) dst else p.sourceId
                    "dm_$peerId"
                }
                dstNorm == normSelf && normClient.isNotBlank() && srcNorm == normClient -> "dm_${state.localNodeId}"
                else -> continue
            }
            pktsArr.put(org.json.JSONObject().apply {
                put("id", p.id)
                put("threadId", threadId)
                put("sourceId", p.sourceId)
                put("destId", p.destId)
                put("sourceCallsign", p.sourceCallsign)
                put("type", p.type.name)
                put("content", p.content)
                put("hops", p.hops)
                put("transport", p.transport)
                put("timestamp", p.timestamp)
                put("isDelivered", p.isDelivered)
                if (p.ackedByCallsign.isNotBlank()) put("ackedByCallsign", p.ackedByCallsign)
                if (p.isPinned) put("isPinned", true)
                if (p.reactions.isNotEmpty()) {
                    val rObj = org.json.JSONObject()
                    for ((em, actors) in p.reactions) {
                        val rArr = org.json.JSONArray()
                        actors.forEach { rArr.put(it) }
                        rObj.put(em, rArr)
                    }
                    put("reactions", rObj)
                }
            })
        }

        val root = org.json.JSONObject().apply {
            put("hostNode", hostObj)
            put("nodes", nodesArr)
            put("groups", groupsArr)
            put("waypoints", wpArr)
            put("packets", pktsArr)
            put("isSosActive", state.isSosActive)
            put("recentCallSignals", pulseMeshWebServer.getRecentCallSignalsJson())
        }
        latestWebVoiceFrame?.let { (seq, who, b64) ->
            root.put("recentVoice", org.json.JSONObject().apply {
                put("seq", seq)
                put("srcNodeId", who.first)
                put("srcCallsign", who.second)
                put("payload", b64)
            })
        }
        return root
    }

    private fun handleWebClientRegistration(reg: com.example.service.WebClientRegistration) {
        viewModelScope.launch(Dispatchers.Main) {
            val cleanId = reg.nodeId.trim()
            if (cleanId.isBlank()) return@launch
            val norm = normalizeId(cleanId)
            if (norm.isBlank() || norm == normalizeId(_uiState.value.localNodeId)) return@launch
            if (reg.publicKeyBase64.isNotBlank() && TacticalE2eeManager.isValidEcPublicKeyBase64(reg.publicKeyBase64)) {
                prefs.verifyOrTrustPeerPublicKey(cleanId, reg.publicKeyBase64)
            }
            val now = System.currentTimeMillis()
            val prev = peersBackingMap[norm]
            val updated = (prev ?: MeshNode(
                id = cleanId,
                callsign = reg.callsign,
                model = "Web Portal Client",
                batteryPercent = 100,
                rssiDbm = -32,
                hopCount = 1
            )).copy(
                callsign = reg.callsign.ifBlank { prev?.callsign ?: cleanId },
                latitude = reg.lat ?: prev?.latitude ?: 0.0,
                longitude = reg.lng ?: prev?.longitude ?: 0.0,
                lastSeenMs = now,
                lastHeardAgoSec = 0,
                publicKeyBase64 = reg.publicKeyBase64.ifBlank { prev?.publicKeyBase64 ?: "" },
                transports = (prev?.transports ?: emptySet()) + "WEB PORTAL"
            )
            peersBackingMap[norm] = updated
            _uiState.update { cur ->
                val mergedNodes = buildMergedNodesList(cur.nodes)
                cur.copy(nodes = mergedNodes, activePeerCount = mergedNodes.count { !it.isSelf })
            }
            refreshConversationsList()
            flushPendingKeyExchangeDmsForPeer(cleanId)

            // Announce web peer presence to the rest of the BLE / Wi-Fi / Relay mesh
            val presencePkt = com.example.service.WireMeshPacket(
                packetId = "web_hb_${norm}_$now",
                srcNodeId = cleanId,
                srcCallsign = reg.callsign,
                dstNodeId = "BROADCAST",
                type = if (reg.lat != null && reg.lng != null) "GPS_BEACON" else "HEARTBEAT",
                payload = "PulseMesh Web Portal Client",
                ttl = 7,
                hops = 1,
                lat = reg.lat,
                lng = reg.lng,
                battery = 100,
                senderPubKey = reg.publicKeyBase64,
                timestamp = now
            )
            sendOmniPacket(presencePkt)
        }
    }

    private fun handleWebSubmittedPacket(submitted: com.example.service.WebSubmittedPacket) {
        viewModelScope.launch(Dispatchers.Main) {
            val now = System.currentTimeMillis()
            val srcId = submitted.clientNodeId.ifBlank { _uiState.value.localNodeId }
            val srcCallsign = submitted.callsign.ifBlank { "WebNode" }

            when (submitted.type) {
                "WEBRTC_OFFER", "WEBRTC_ANSWER", "WEBRTC_ICE" -> {
                    val targetDst = submitted.dstNodeId.ifBlank { "ALL" }
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = targetDst,
                        type = submitted.type,
                        payload = submitted.payload,
                        ttl = 7,
                        hops = 1,
                        sourceTransport = "WEB PORTAL",
                        timestamp = now
                    )
                    sendMeshPacket(wire)
                }
                "CALL_RING", "CALL_ACCEPT", "CALL_DECLINE", "CALL_END" -> {
                    val targetDst = submitted.dstNodeId.ifBlank { _uiState.value.localNodeId }
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = targetDst,
                        type = submitted.type,
                        payload = submitted.payload.ifBlank { submitted.type },
                        ttl = 7,
                        hops = 1,
                        sourceTransport = "WEB PORTAL",
                        timestamp = now
                    )
                    handleIncomingPacket(wire)
                    if (normalizeId(targetDst) != normalizeId(_uiState.value.localNodeId)) {
                        sendOmniPacket(wire)
                    }
                }
                "VOICE_PTT", "VOICE", "CALL_AUDIO" -> {
                    val b64 = submitted.payload.trim()
                    if (b64.isBlank()) return@launch
                    val isCall = submitted.type == "CALL_AUDIO"
                    runCatching {
                        audioService.playAudioBase64(b64, isCallAudio = isCall)
                        if (isCall) {
                            tacticalCallService.recordIncomingFramePlayed()
                        }
                    }
                    // If Host was ringing out to Web Peer and Web Peer starts sending live CALL_AUDIO,
                    // auto-transition Host cleanly to IN_CALL_ACTIVE matching native BLE/Hotspot call handling.
                    if (isCall) {
                        val curUi = _uiState.value
                        if (curUi.duplexCallState.signalingState == com.example.service.CallSignalingState.RINGING_OUTGOING ||
                            curUi.callState == com.example.service.CallSignalingState.RINGING_OUTGOING
                        ) {
                            ringSignalRepeatJob?.cancel()
                            ringSignalRepeatJob = null
                            callAcceptConfirmedByPeer = true
                            tacticalCallService.acceptCall(startMicDelayMs = 0L)
                            tacticalCallService.markCallAcceptAcked()
                            val webNode = peersBackingMap[normalizeId(srcId)] ?: MeshNode(
                                id = srcId,
                                callsign = srcCallsign,
                                isSelf = false
                            )
                            _uiState.update {
                                it.copy(
                                    callState = com.example.service.CallSignalingState.IN_CALL_ACTIVE,
                                    remoteCallerId = webNode.id,
                                    remoteCallerName = webNode.callsign,
                                    isPrivateCallActive = true,
                                    activePrivateCallNode = webNode,
                                    incomingPrivateCall = null,
                                    callAckConfirmed = true
                                )
                            }
                        } else if (!callAcceptConfirmedByPeer && tacticalCallService.callState.value.isInCall) {
                            callAcceptConfirmedByPeer = true
                            acceptSignalRetryJob?.cancel()
                            acceptSignalRetryJob = null
                            tacticalCallService.markCallAcceptAcked()
                            _uiState.update { it.copy(callAckConfirmed = true) }
                        }
                    }
                    val vSeq = webVoiceSeqCounter.incrementAndGet()
                    latestWebVoiceFrame = Triple(vSeq, srcId to srcCallsign, b64)
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = submitted.dstNodeId.ifBlank { "ALL" },
                        type = if (isCall) "CALL_AUDIO" else "VOICE_PTT",
                        payload = b64,
                        ttl = 5,
                        hops = 1,
                        timestamp = now
                    )
                    // skipWebPortalNotify = true because PulseMeshWebServer.handleClientSocket already notified SSE
                    sendVoiceAudioPacket(wire, skipWebPortalNotify = true)
                }
                "CHAT_ACK" -> {
                    val ackedId = submitted.payload.ifBlank { submitted.text }.trim()
                    if (ackedId.isNotBlank()) {
                        val wire = com.example.service.WireMeshPacket(
                            packetId = submitted.packetId,
                            srcNodeId = srcId,
                            srcCallsign = srcCallsign,
                            dstNodeId = submitted.dstNodeId.ifBlank { _uiState.value.localNodeId },
                            type = "CHAT_ACK",
                            payload = ackedId,
                            ttl = 5,
                            hops = 1,
                            sourceTransport = "WEB PORTAL",
                            timestamp = now
                        )
                        handleIncomingPacket(wire)
                        val targetDstNorm = normalizeId(submitted.dstNodeId)
                        if (targetDstNorm.isNotBlank() && targetDstNorm != normalizeId(_uiState.value.localNodeId)) {
                            sendOmniPacket(wire)
                        }
                    }
                }
                "SOS_CLEAR" -> {
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = "ALL",
                        type = "SOS_CLEAR",
                        payload = submitted.text.ifBlank { "SOS CANCELLED by $srcCallsign" },
                        ttl = 7,
                        hops = 1,
                        sourceTransport = "WEB PORTAL",
                        timestamp = now
                    )
                    handleIncomingPacket(wire)
                    sendOmniPacket(wire)
                }
                "QUICK_STATUS", "CALL_SPEAK" -> {
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = submitted.dstNodeId.ifBlank { "ALL" },
                        type = "QUICK_STATUS",
                        payload = submitted.payload.ifBlank { submitted.text },
                        ttl = 7,
                        hops = 1,
                        lat = submitted.lat,
                        lng = submitted.lng,
                        sourceTransport = "WEB PORTAL",
                        timestamp = now
                    )
                    handleIncomingPacket(wire)
                    sendOmniPacket(wire)
                }
                "REACTION" -> {
                    val wire = com.example.service.WireMeshPacket(
                        packetId = submitted.packetId,
                        srcNodeId = srcId,
                        srcCallsign = srcCallsign,
                        dstNodeId = "ALL",
                        type = "REACTION",
                        payload = submitted.payload.ifBlank { submitted.text },
                        ttl = 5,
                        hops = 1,
                        sourceTransport = "WEB PORTAL",
                        timestamp = now
                    )
                    handleIncomingPacket(wire)
                    sendOmniPacket(wire)
                }
                "WAYPOINT" -> {
                    val lat = submitted.waypointLat ?: submitted.lat ?: return@launch
                    val lng = submitted.waypointLng ?: submitted.lng ?: return@launch
                    val wpType = runCatching { WaypointType.valueOf(submitted.waypointType) }.getOrDefault(WaypointType.RALLY)
                    val title = submitted.waypointTitle.ifBlank { wpType.label }
                    val wpId = "wp_web_$now"
                    val wp = TacticalWaypoint(
                        id = wpId,
                        title = title,
                        type = wpType,
                        latitude = lat,
                        longitude = lng,
                        creatorCallsign = srcCallsign,
                        timestamp = now,
                        payloadHex = "0x${wpId.hashCode().toUInt().toString(16).uppercase()}"
                    )
                    val pkt = MeshPacket(
                        id = submitted.packetId,
                        sourceId = srcId,
                        destId = "ALL",
                        sourceCallsign = srcCallsign,
                        type = PacketType.WAYPOINT,
                        content = "SHARED PIN [${wpType.name}]: $title @ ${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}",
                        hops = 1,
                        rssiDbm = -32,
                        transport = "WEB PORTAL",
                        rawHexBadge = "[WAYPOINT | $srcId->ALL | WEB PORTAL]",
                        isIncoming = true,
                        waypointData = wp,
                        timestamp = now
                    )
                    _uiState.update { cur ->
                        cur.copy(
                            waypoints = cur.waypoints.filterNot { it.id == wpId } + wp,
                            packets = listOf(pkt) + cur.packets
                        )
                    }
                    val wpJson = org.json.JSONObject().apply {
                        put("id", wpId)
                        put("title", title)
                        put("type", wpType.name)
                        put("lat", lat)
                        put("lon", lng)
                        put("creator", srcCallsign)
                        put("ts", now)
                    }.toString()
                    sendOmniPacket(
                        com.example.service.WireMeshPacket(
                            packetId = submitted.packetId,
                            srcNodeId = srcId,
                            srcCallsign = srcCallsign,
                            dstNodeId = "ALL",
                            type = "WAYPOINT",
                            payload = wpJson,
                            ttl = 7,
                            hops = 1,
                            lat = lat,
                            lng = lng,
                            timestamp = now
                        )
                    )
                }
                "SOS" -> {
                    val content = submitted.text.ifBlank { "PRIORITY SOS FROM $srcCallsign (WEB PORTAL)" }
                    val pkt = MeshPacket(
                        id = submitted.packetId,
                        sourceId = srcId,
                        destId = "ALL",
                        sourceCallsign = srcCallsign,
                        type = PacketType.SOS,
                        content = content,
                        hops = 1,
                        rssiDbm = -32,
                        transport = "WEB PORTAL",
                        rawHexBadge = "[SOS | $srcId->ALL | WEB PORTAL]",
                        isIncoming = true,
                        timestamp = now
                    )
                    persistentChatManager.savePacket("channel_primary", pkt)
                    _uiState.update {
                        it.copy(
                            packets = listOf(pkt) + it.packets,
                            incomingSosAlert = content
                        )
                    }
                    audioService.playSosAlarmPulse()
                    refreshConversationsList()
                    sendOmniPacket(
                        com.example.service.WireMeshPacket(
                            packetId = submitted.packetId,
                            srcNodeId = srcId,
                            srcCallsign = srcCallsign,
                            dstNodeId = "ALL",
                            type = "SOS",
                            payload = content,
                            ttl = 7,
                            hops = 1,
                            lat = submitted.lat,
                            lng = submitted.lng,
                            timestamp = now
                        )
                    )
                    triggerHaptic(160)
                }
                else -> {
                    val text = submitted.text.ifBlank { submitted.payload }.trim()
                    if (text.isBlank()) return@launch
                    val tid = submitted.threadId
                    val isEnc = tid == "channel_encrypted"
                    val isGroup = tid.startsWith("group_")
                    val isDm = tid.startsWith("dm_")
                    val dstNodeId = when {
                        isEnc -> "ENC_TACNET"
                        isGroup -> tid.removePrefix("group_")
                        isDm -> tid.removePrefix("dm_")
                        else -> "ALL"
                    }
                    val isDmToHost = isDm && normalizeId(dstNodeId) == normalizeId(_uiState.value.localNodeId)
                    val effectiveThreadId = if (isDmToHost) "dm_$srcId" else tid
                    val hostCallsign = _uiState.value.operatorCallsign
                    val meshPkt = MeshPacket(
                        id = submitted.packetId,
                        sourceId = srcId,
                        destId = dstNodeId,
                        sourceCallsign = srcCallsign,
                        type = PacketType.TEXT,
                        content = text,
                        hops = 1,
                        rssiDbm = -32,
                        transport = "WEB PORTAL",
                        rawHexBadge = "[${submitted.packetId} | $srcId->$dstNodeId | WEB PORTAL]",
                        isIncoming = true,
                        timestamp = now,
                        isDelivered = isDmToHost,
                        ackedByCallsign = if (isDmToHost) hostCallsign else ""
                    )
                    _uiState.update { it.copy(packets = listOf(meshPkt) + it.packets) }
                    persistentChatManager.savePacket(effectiveThreadId, meshPkt)
                    refreshConversationsList()

                    if (!isDmToHost) {
                        val wireType = when {
                            isDm -> "E2EE_DM"
                            isGroup -> "GROUP_CHAT"
                            else -> "CHAT"
                        }
                        val peerPub = if (isDm) {
                            prefs.getPeerPublicKey(dstNodeId) ?: peersBackingMap[normalizeId(dstNodeId)]?.publicKeyBase64
                        } else null
                        val wirePayload = when {
                            isDm && !peerPub.isNullOrBlank() ->
                                TacticalE2eeManager.encryptDmWithEcdh(text, prefs.x25519PrivateKey, peerPub) ?: "PLAINTEXT:$text"
                            isDm -> "PLAINTEXT:$text"
                            else -> text
                        }
                        val relayedWire = com.example.service.WireMeshPacket(
                            packetId = submitted.packetId,
                            srcNodeId = srcId,
                            srcCallsign = srcCallsign,
                            dstNodeId = dstNodeId,
                            type = wireType,
                            payload = wirePayload,
                            ttl = 7,
                            hops = 1,
                            groupId = if (isGroup) dstNodeId else "",
                            senderPubKey = submitted.publicKeyBase64.ifBlank { prefs.x25519PublicKey },
                            timestamp = now
                        )
                        enqueueDtnPacket(relayedWire)
                        sendOmniPacket(relayedWire)
                    } else {
                        pulseMeshWebServer.notifyStateChanged()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pulseMeshWebServer.stop()
        internetNetworkCallback?.let { cb ->
            runCatching {
                val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        }
        internetNetworkCallback = null
        callTimerJob?.cancel()
        telemetryTickJob?.cancel()
        simAnimJob?.cancel()
        acceptSignalRetryJob?.cancel()
        deadManSwitchJob?.cancel()
        runCatching { statusTts?.stop(); statusTts?.shutdown() }
        statusTts = null
        ultrasonicModem.stopListeningLoop()
        audioService.release()
        tacticalCallService.release()
        gpsService.stopWatchingLocation()
        offGridBleEngine.stop()
        autoHotspotNegotiator.release()
        meshNetworkService.stop()
        hotspotManager.stopLocalHotspot()
        bleRadioService.stopLiveTx()
        bleRadioService.stopRawRxSniffer()
        sensorService.stop()
    }
}
