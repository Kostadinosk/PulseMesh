package com.example.model

enum class WaypointType(val label: String, val icon: String, val colorHex: Long) {
    RALLY("Rally Point", "FLAG", 0xFF00E676),
    HAZARD("Hazard / Obstacle", "WARNING", 0xFFFF5252),
    SUPPLY("Supply Cache", "INVENTORY", 0xFFFFAB00),
    SOS("Emergency SOS Pin", "SOS", 0xFFFF1744),
    MEETUP("Meetup Point", "FLAG", 0xFF00E676),
    CAMP("Camp / Base", "HOME", 0xFF00B0FF),
    WATER("Water Source", "WATER", 0xFF00E5FF),
    CACHE("Supply Cache", "INVENTORY", 0xFFFF9100)
}

data class TacticalWaypoint(
    val id: String,
    val title: String,
    val type: WaypointType,
    val latitude: Double,
    val longitude: Double,
    val creatorCallsign: String,
    val timestamp: Long,
    val payloadHex: String
)

enum class PacketType {
    TEXT,
    GPS_BEACON,
    WAYPOINT,
    VOICE_BURST,
    SOS
}

enum class LocationShareMode(val label: String) {
    OFF("OFF"),
    COARSE("COARSE"),
    EXACT("EXACT")
}

data class MeshGroup(
    val groupId: String,
    val groupName: String,
    val memberIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val groupKeyBase64: String = ""
)

data class MeshPacket(
    val id: String,
    val sourceId: String,
    val destId: String,
    val sourceCallsign: String,
    val type: PacketType,
    val content: String,
    val hops: Int,
    val maxHops: Int = 5,
    val rssiDbm: Int? = null,
    val transport: String = "BLE 1M",
    val timestamp: Long = System.currentTimeMillis(),
    val rawHexBadge: String,
    val isIncoming: Boolean,
    val waypointData: TacticalWaypoint? = null,
    val isDelivered: Boolean = false,
    val ackedByCallsign: String = ""
) {
    val transportUsed: String
        get() = transport
}

enum class ChipsetProfile(
    val title: String,
    val chipModel: String,
    val features: String,
    val mtuBytes: Int,
    val driverInfo: String,
    val maxTxPower: String,
    val usesDualPhyBridge: Boolean
) {
    QUALCOMM_SNAPDRAGON(
        title = "Qualcomm Snapdragon",
        chipModel = "FastConnect 7800 / Snapdragon 8 Elite (OnePlus 15)",
        features = "BLE Coded S=8 (125kbps FEC) + WCNSS Root Boost +25dBm",
        mtuBytes = 240,
        driverInfo = "qca_cld3 / WCNSS_qcom_cfg.ini",
        maxTxPower = "+25 dBm (316 mW)",
        usesDualPhyBridge = false
    ),
    MEDIATEK_DIMENSITY(
        title = "MediaTek Dimensity",
        chipModel = "Dimensity 9400 / 9300+ (Redmi / POCO / Vivo)",
        features = "BLE Coded / Ext 1M + wlan_gen4m Root Boost +23dBm",
        mtuBytes = 240,
        driverInfo = "wlan_gen4m / connac3x",
        maxTxPower = "+23 dBm (200 mW)",
        usesDualPhyBridge = false
    ),
    SAMSUNG_EXYNOS_TENSOR(
        title = "Samsung Exynos / Tensor",
        chipModel = "Google Tensor G4 / Exynos 2400 (Pixel / Galaxy)",
        features = "bcmdhd driver + OneUI Freecess / Pixel Standby bypass",
        mtuBytes = 240,
        driverInfo = "bcmdhd / brcmfmac",
        maxTxPower = "+20 dBm (100 mW)",
        usesDualPhyBridge = false
    ),
    LEGACY_BUDGET_BLE42(
        title = "Legacy Budget Phone",
        chipModel = "Legacy BLE 4.2 Generic (Dual-PHY Bridge Active)",
        features = "Dual-PHY Bridge Mode (20-byte packet chunking active)",
        mtuBytes = 20,
        driverInfo = "Standard AOSP HCI Driver",
        maxTxPower = "+10 dBm (10 mW)",
        usesDualPhyBridge = true
    )
}

data class OemBypassStatus(
    val colorOsHans: Boolean = true,
    val hyperOsMillet: Boolean = true,
    val oneUiFreecess: Boolean = true,
    val aospPhantomKiller: Boolean = true
)

enum class VoiceTransportMode(
    val title: String,
    val protocol: String,
    val bitrate: String,
    val latency: String,
    val minRssiRequired: Int
) {
    FULL_DUPLEX_OPUS(
        title = "Full-Duplex Live Call",
        protocol = "Wi-Fi Aware UDP (1-Hop)",
        bitrate = "16 kbps Opus VBR",
        latency = "< 45 ms",
        minRssiRequired = -75
    ),
    MULTI_HOP_BURST(
        title = "Multi-Hop PTT Burst",
        protocol = "BLE Coded S=8 / 0xDD Beacons",
        bitrate = "1200 bps Sub-Frame (150 B/s)",
        latency = "240 ms / hop",
        minRssiRequired = -98
    ),
    GHOST_VOICE(
        title = "Ghost Voice (Micro-Packet)",
        protocol = "Offline STT-to-TTS 40B Tokens",
        bitrate = "320 bps Micro-Frame",
        latency = "Extreme Range (-108 dBm)",
        minRssiRequired = -108
    )
}

data class ChatConversation(
    val threadId: String,
    val title: String,
    val isChannel: Boolean,
    val isEncrypted: Boolean,
    val peerNodeId: String? = null,
    val peerCallsign: String? = null,
    val lastMessageText: String = "",
    val lastTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isOnline: Boolean = true,
    val isGroup: Boolean = false,
    val groupId: String? = null,
    val memberIds: List<String> = emptyList(),
    val safetyFingerprint: String = "",
    val isKeyChangedWarning: Boolean = false
)

enum class MapRenderMode(val title: String) {
    VECTOR_LEAFLET("Vector Dark Map"),
    TACTICAL_RADAR("HUD Radar Grid"),
    SATELLITE_DARK("Satellite Dark")
}

data class MeshNode(
    val id: String,
    val callsign: String,
    val model: String = "Mesh Peer",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val batteryPercent: Int = 85,
    val rssiDbm: Int = -55,
    val snrDb: Double = 12.0,
    val hopCount: Int = 1,
    val lastHeardAgoSec: Int = 0,
    val isSelf: Boolean = false,
    val isRepeater: Boolean = false,
    val isGateway: Boolean = false,
    val chipsetProfile: ChipsetProfile = ChipsetProfile.QUALCOMM_SNAPDRAGON,
    val isTransmittingVoice: Boolean = false,
    val distanceMeters: Double? = null,
    val ipAddress: String = "",
    val isHotspotHost: Boolean = false,
    val hotspotSsid: String = "",
    val hotspotPass: String = "",
    val lastBlePhy: String = "BLE 1M",
    val lastSeenMs: Long = System.currentTimeMillis(),
    val transports: Set<String> = emptySet(),
    val publicKeyBase64: String = "",
    val safetyFingerprint: String = "",
    val isKeyChangedWarning: Boolean = false
)
