// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BleMeshTelemetry(
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val bleTxStatus: String = "BLE TX: IDLE",
    val txFramesCount: Int = 0,
    val rxFramesCount: Int = 0,
    val lastRssiDbm: Int = 0,
    val activePeersCount: Int = 0,
    val statusText: String = "BLE Mesh Ready",
    val gattAckedChunksCount: Int = 0,
    val connectedServerDeviceAddresses: List<String> = emptyList(),
    val connectedGattClientAddresses: List<String> = emptyList(),
    val isTwoWayGattSymmetric: Boolean = false,
    val activeL2capChannelsCount: Int = 0,
    val localL2capPsm: Int = 0
)

data class DecodedCafeBeacon(
    val nodeIdHex: String,
    val batteryPercent: Int,
    val flags: Int,
    val latitude: Double?,
    val longitude: Double?,
    val callsignPrefix: String,
    val callStateByte: Int = 0x00,
    val hotspotRoleByte: Int = 0x00,
    val targetNodeIdHex: String = "ALL",
    val isHotspotHost: Boolean = false,
    val isSos: Boolean = false
)

class OffGridBleMeshEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localNodeId: () -> String,
    private val localCallsign: () -> String,
    private val getCurrentLocation: () -> Pair<Double?, Double?>,
    private val getBatteryPercent: () -> Int,
    private val getLocationAccuracy: () -> Float? = { null },
    private val getHotspotRoleByte: () -> Int = { 0 },
    private val shouldBroadcastCoarseLocation: () -> Boolean = { false },
    private val getLocationShareMode: () -> com.example.model.LocationShareMode = { com.example.model.LocationShareMode.EXACT }
) {
    companion object {
        const val MANUFACTURER_ID_CAFE = 0xCAFE
        const val MANUFACTURER_ID_CAFF = 0xCAFF
        const val MAX_OUTGOING_GATT_CLIENTS = 7

        // Call state bits inside hotspotRoleByte (bits 1..2; Bit 0 is isHotspotHost, Bit 7 is isSos)
        const val CALL_STATE_BYTE_IDLE = 0x00
        const val CALL_STATE_BYTE_RINGING_OUTGOING = 0x01
        const val CALL_STATE_BYTE_IN_CALL_ACTIVE = 0x02
        const val CALL_STATE_BYTE_CALL_ENDED = 0x03

        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000CAFE-0000-1000-8000-00805F9B34FB")
        val MESH_SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(MESH_SERVICE_UUID)
        val VOICE_CHAR_UUID: UUID = UUID.fromString("0000CAFF-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /**
         * 3-Option Location Privacy Selector (`OFF`, `COARSE`, `EXACT`):
         * - `OFF`: returns `0.0 to 0.0` (all zero bytes in `0xCAFE` BLE beacon and `0.0, 0.0` in outgoing JSON packets).
         * - `COARSE`: rounds outgoing latitude and longitude to 2 decimal places (`round(coord * 100.0) / 100.0`, ~1.1 km grid).
         * - `EXACT`: broadcasts full 6-decimal-place hardware GPS precision (`round(coord * 1_000_000.0) / 1_000_000.0`).
         */
        fun applyLocationShareMode(
            lat: Double?,
            lon: Double?,
            mode: com.example.model.LocationShareMode
        ): Pair<Double, Double> {
            if (mode == com.example.model.LocationShareMode.OFF || lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
                return Pair(0.0, 0.0)
            }
            return when (mode) {
                com.example.model.LocationShareMode.OFF -> Pair(0.0, 0.0)
                com.example.model.LocationShareMode.COARSE -> {
                    val coarseLat = kotlin.math.round(lat * 100.0) / 100.0
                    val coarseLon = kotlin.math.round(lon * 100.0) / 100.0
                    Pair(coarseLat, coarseLon)
                }
                com.example.model.LocationShareMode.EXACT -> {
                    val exactLat = kotlin.math.round(lat * 1_000_000.0) / 1_000_000.0
                    val exactLon = kotlin.math.round(lon * 1_000_000.0) / 1_000_000.0
                    Pair(exactLat, exactLon)
                }
            }
        }

        fun prepareBeaconCoordinates(
            lat: Double?,
            lon: Double?,
            broadcastCoarseLocation: Boolean
        ): Pair<Double?, Double?> {
            if (!broadcastCoarseLocation || lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
                return Pair(null, null)
            }
            val coarseLat = kotlin.math.round(lat * 100.0) / 100.0
            val coarseLon = kotlin.math.round(lon * 100.0) / 100.0
            return Pair(coarseLat, coarseLon)
        }

        fun normalizeId(id: String): String {
            return id.trim().uppercase().removePrefix("0X").removePrefix("NODE-").removePrefix("PEER-").takeLast(4)
        }

        /**
         * Deterministic GATT Role Staggering to prevent GATT_ERROR 133 collisions when two phones discover each other simultaneously:
         * - If `myNodeId > peerNodeId`: wait `150ms` and initiate `connectGatt`.
         * - If `myNodeId < peerNodeId`: wait `1500ms` and only call `connectGatt` if the peer has not already connected to our `BluetoothGattServer`.
         */
        fun computeGattRoleStaggerDelayMs(myNodeId: String, peerNodeId: String): Long {
            val myInt = normalizeId(myNodeId).toIntOrNull(16) ?: 0
            val peerInt = normalizeId(peerNodeId).toIntOrNull(16) ?: 0
            if (myInt != 0 && peerInt != 0 && myInt != peerInt) {
                return if (myInt > peerInt) 150L else 1500L
            }
            return if (myNodeId >= peerNodeId) 150L else 1500L
        }

        /**
         * Returns true ONLY for user-triggered packet types allowed on BLE GATT (`0000CAFF`).
         * NEVER sends `HEARTBEAT`, `GPS_BEACON`, or `MUTUAL_PING` over BLE GATT!
         */
        fun isEligibleForBleGattPipe(type: String): Boolean {
            val norm = type.trim().uppercase()
            if (norm.isEmpty()) return false
            if (norm == "HEARTBEAT" || norm == "GPS_BEACON" || norm == "MUTUAL_PING") return false
            return true
        }

        fun isBlockedFakeCallsign(callsign: String): Boolean {
            val c = callsign.trim()
            if (c.isBlank() || !c.any { it.isLetterOrDigit() }) return true
            val lower = c.lowercase()
            return c.equals("Peer Node", ignoreCase = true) ||
                c.equals("Remote Operator", ignoreCase = true) ||
                c.equals("Unknown", ignoreCase = true) ||
                c.equals("Bot", ignoreCase = true) ||
                c.equals("Mesh Peer", ignoreCase = true) ||
                c.equals("Mesh Relay", ignoreCase = true) ||
                c.equals("Security Gateway", ignoreCase = true) ||
                c.equals("Discovered Mesh Peer", ignoreCase = true) ||
                c.equals("N/A", ignoreCase = true) ||
                c.equals("Server", ignoreCase = true) ||
                c.equals("Serve", ignoreCase = true) ||
                c.equals("Demo", ignoreCase = true) ||
                c.equals("Fake", ignoreCase = true) ||
                c.equals("Simulator", ignoreCase = true) ||
                c.equals("Emulator", ignoreCase = true) ||
                c.equals("Test", ignoreCase = true) ||
                c.equals("Test Node", ignoreCase = true) ||
                c.startsWith("Peer Node", ignoreCase = true) ||
                c.startsWith("Peer-", ignoreCase = true) ||
                c.startsWith("Peer ", ignoreCase = true) ||
                c.startsWith("Remote Operator", ignoreCase = true) ||
                c.startsWith("Bot-", ignoreCase = true) ||
                c.startsWith("Phone 1", ignoreCase = true) ||
                c.startsWith("Phone 2", ignoreCase = true) ||
                c.startsWith("Phone-1", ignoreCase = true) ||
                c.startsWith("Phone-2", ignoreCase = true) ||
                lower.startsWith("phone1") ||
                lower.startsWith("phone2") ||
                lower.startsWith("server-") ||
                lower.startsWith("server ") ||
                lower.startsWith("mock") ||
                lower.contains("phone 1 serv") ||
                lower.contains("fake") ||
                lower.contains("dummy") ||
                lower.contains("simulated") ||
                c.contains("---") ||
                c.contains("———")
        }

        /**
         * Packs the proven 12-byte 0xCAFE Manufacturer Data beacon in AdvertiseData:
         * `[0x50, 0x4D, nodeIdHi, nodeIdLo, batteryPct, hotspotRoleByte, latFloat3B, lonFloat3B]`
         * - Bytes 0..1: `0x50, 0x4D` ('P', 'M')
         * - Bytes 2..3: `nodeIdHi, nodeIdLo`
         * - Byte 4: `batteryPct` (0..100)
         * - Byte 5: `hotspotRoleByte` (bits 0..1 = callStateByte, bit 4 = hotspotHost, bit 7 = isSos)
         * - Bytes 6..8: `latFloat3B` (24-bit signed fixed-point: `(lat * 40000.0).toInt()`)
         * - Bytes 9..11: `lonFloat3B` (24-bit signed fixed-point: `(lon * 40000.0).toInt()`)
         */
        fun pack12ByteCafeBeacon(
            nodeIdHex: String,
            batteryPercent: Int,
            hotspotRoleByte: Int,
            lat: Double?,
            lon: Double?
        ): ByteArray {
            val srcShort = normalizeId(nodeIdHex).toIntOrNull(16) ?: 0
            val batByte = batteryPercent.coerceIn(0, 100).toByte()
            val roleByte = (hotspotRoleByte and 0xFF).toByte()
            val lat24 = if (lat != null && lat != 0.0) {
                (lat.coerceIn(-90.0, 90.0) * 40000.0).toInt()
            } else {
                0
            }
            val lon24 = if (lon != null && lon != 0.0) {
                (lon.coerceIn(-180.0, 180.0) * 40000.0).toInt()
            } else {
                0
            }

            return byteArrayOf(
                0x50.toByte(), // Byte 0: 'P'
                0x4D.toByte(), // Byte 1: 'M'
                ((srcShort ushr 8) and 0xFF).toByte(), // Byte 2: nodeIdHi
                (srcShort and 0xFF).toByte(),          // Byte 3: nodeIdLo
                batByte,                               // Byte 4: batteryPct
                roleByte,                              // Byte 5: hotspotRoleByte
                ((lat24 ushr 16) and 0xFF).toByte(),   // Byte 6: latFloat3B[0]
                ((lat24 ushr 8) and 0xFF).toByte(),    // Byte 7: latFloat3B[1]
                (lat24 and 0xFF).toByte(),             // Byte 8: latFloat3B[2]
                ((lon24 ushr 16) and 0xFF).toByte(),   // Byte 9: lonFloat3B[0]
                ((lon24 ushr 8) and 0xFF).toByte(),    // Byte 10: lonFloat3B[1]
                (lon24 and 0xFF).toByte()              // Byte 11: lonFloat3B[2]
            )
        }

        /**
         * Packs 14-byte 0xCAFE payload for backward-compatible tests/beacons:
         * `[nodeId: 2B | battery: 1B (0..100) | flags: 1B | latMicroDeg: 4B Int | lonMicroDeg: 4B Int | callsignPrefix: 2B]`
         */
        fun pack14ByteCafePayload(
            nodeIdHex: String,
            batteryPercent: Int,
            isSos: Boolean,
            lat: Double?,
            lon: Double?,
            callsign: String
        ): ByteArray {
            val srcShort = normalizeId(nodeIdHex).toIntOrNull(16)?.toShort() ?: 0
            val batByte = batteryPercent.coerceIn(0, 100).toByte()
            val flagsByte: Byte = if (isSos) 1 else 0
            val latMicro = if (lat != null && lat != 0.0) (lat * 10_000_000.0).toInt() else 0
            val lonMicro = if (lon != null && lon != 0.0) (lon * 10_000_000.0).toInt() else 0
            val csPrefix = callsign.padEnd(2, ' ').take(2).toByteArray(Charsets.US_ASCII)

            val buffer = ByteBuffer.allocate(14)
            buffer.putShort(srcShort)
            buffer.put(batByte)
            buffer.put(flagsByte)
            buffer.putInt(latMicro)
            buffer.putInt(lonMicro)
            buffer.put(if (csPrefix.isNotEmpty()) csPrefix[0] else 'P'.code.toByte())
            buffer.put(if (csPrefix.size > 1) csPrefix[1] else 'M'.code.toByte())
            return buffer.array()
        }

        /**
         * Unpacks:
         * 1) 12-byte `[0x50, 0x4D, nodeIdHi, nodeIdLo, batteryPct, hotspotRoleByte, latFloat3B, lonFloat3B]`
         *    - Bit 0 (0x01) of hotspotRoleByte = isHotspotHost (1 = hosting Wi-Fi Direct / Local Hotspot)
         *    - Bits 1..2 (0x06) of hotspotRoleByte = callStateByte
         *    - Bit 7 (0x80) of hotspotRoleByte = isSos
         * 2) 14-byte legacy `[nodeId: 2B | battery: 1B | flags: 1B | latMicroDeg: 4B | lonMicroDeg: 4B | callsignPrefix: 2B]`
         */
        fun unpackCafePayload(mfgBytes: ByteArray): DecodedCafeBeacon? {
            if (mfgBytes.size == 12 && mfgBytes[0] == 0x50.toByte() && mfgBytes[1] == 0x4D.toByte()) {
                val srcShort = ((mfgBytes[2].toInt() and 0xFF) shl 8) or (mfgBytes[3].toInt() and 0xFF)
                val bat = (mfgBytes[4].toInt() and 0xFF).coerceIn(0, 100)
                val roleByte = mfgBytes[5].toInt() and 0xFF
                val isHost = (roleByte and 0x01) != 0
                val callState = (roleByte ushr 1) and 0x03
                val isSosFlag = (roleByte and 0x80) != 0

                val lat24 = (mfgBytes[6].toInt() shl 16) or
                    ((mfgBytes[7].toInt() and 0xFF) shl 8) or
                    (mfgBytes[8].toInt() and 0xFF)
                val lon24 = (mfgBytes[9].toInt() shl 16) or
                    ((mfgBytes[10].toInt() and 0xFF) shl 8) or
                    (mfgBytes[11].toInt() and 0xFF)

                val lat = if (lat24 != 0) lat24 / 40000.0 else null
                val lon = if (lon24 != 0) lon24 / 40000.0 else null

                return DecodedCafeBeacon(
                    nodeIdHex = "0x%04X".format(srcShort),
                    batteryPercent = bat,
                    flags = roleByte,
                    latitude = lat,
                    longitude = lon,
                    callsignPrefix = "PM",
                    callStateByte = callState,
                    hotspotRoleByte = roleByte,
                    targetNodeIdHex = "ALL",
                    isHotspotHost = isHost,
                    isSos = isSosFlag
                )
            }

            // Strict validation for legacy 14-byte PulseMesh beacons so random non-PulseMesh 0xCAFE devices never spawn fake peers
            if (mfgBytes.size != 14) return null
            val buffer = ByteBuffer.wrap(mfgBytes)
            val srcShort = buffer.short
            val batByte = buffer.get()
            val flagsByte = buffer.get()
            val latMicro = buffer.int
            val lonMicro = buffer.int
            val b0 = buffer.get()
            val b1 = buffer.get()
            val c0 = b0.toInt() and 0xFF
            val c1 = b1.toInt() and 0xFF
            if (c0 !in 0x20..0x7E || c1 !in 0x20..0x7E) return null
            val csPrefix = String(byteArrayOf(b0, b1), Charsets.US_ASCII).trim()
            if (csPrefix.isBlank() || !csPrefix.any { it.isLetterOrDigit() }) return null

            val batRaw = batByte.toInt() and 0xFF
            val flagsInt = flagsByte.toInt() and 0xFF
            if (batRaw > 100 || flagsInt > 1) return null

            val nodeIdHex = "0x%04X".format(srcShort)
            val bat = batRaw.coerceIn(0, 100)
            val lat = if (latMicro != 0) latMicro / 10_000_000.0 else null
            val lon = if (lonMicro != 0) lonMicro / 10_000_000.0 else null

            return DecodedCafeBeacon(
                nodeIdHex = nodeIdHex,
                batteryPercent = bat,
                flags = flagsInt,
                latitude = lat,
                longitude = lon,
                callsignPrefix = csPrefix,
                callStateByte = CALL_STATE_BYTE_IDLE,
                hotspotRoleByte = 0,
                targetNodeIdHex = "ALL",
                isHotspotHost = false,
                isSos = (flagsInt == 1)
            )
        }
    }

    private val advMutex = Mutex()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val _telemetry = MutableStateFlow(BleMeshTelemetry())
    val telemetry: StateFlow<BleMeshTelemetry> = _telemetry.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<WireMeshPacket>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingPackets: SharedFlow<WireMeshPacket> = _incomingPackets.asSharedFlow()

    val l2capTransport = BleL2capTransport(
        scope = scope,
        bluetoothAdapterProvider = { bluetoothAdapter },
        isBleReady = { isBleHardwareReady() },
        onJsonLineReceived = { line, device ->
            handleIncomingL2capLine(line, device)
        },
        onActiveChannelsChanged = { count, psm ->
            _telemetry.update { it.copy(activeL2capChannelsCount = count, localL2capPsm = psm) }
        }
    )

    fun hasActiveL2capChannel(): Boolean = l2capTransport.hasActiveChannel()

    private val seenPacketIds = ConcurrentHashMap<String, Long>()
    val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    val knownPeerCallsigns = ConcurrentHashMap<String, String>()
    val knownPeerHotspotSsids = ConcurrentHashMap<String, String>()
    val peerMacByNodeId = ConcurrentHashMap<String, String>()
    val peerNodeIdByMac = ConcurrentHashMap<String, String>()

    private val connectedGattPeers = ConcurrentHashMap<String, BluetoothGatt>()
    val connectedGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val gattClientLastActivityMs = ConcurrentHashMap<String, Long>()
    val peerIsHotspotHost = ConcurrentHashMap<String, Boolean>()
    val peerLastRssi = ConcurrentHashMap<String, Int>()
    val peerLastRssiByMac = ConcurrentHashMap<String, Int>()
    val peerLastBlePhy = ConcurrentHashMap<String, String>()
    val pendingGattConnects: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gattServicesReadyPeers = ConcurrentHashMap<String, Boolean>()
    val clientNegotiatedMtuMap = ConcurrentHashMap<String, Int>()
    private val gattRxStringBuilders = ConcurrentHashMap<String, StringBuilder>()
    private val lastReverseHeartbeatSentMs = ConcurrentHashMap<String, Long>()

    // Single Mutex + dedicated channels for strictly serialized BLE GATT JSON transmissions
    val gattSendMutex = Mutex()
    val gattTxMutex: Mutex get() = gattSendMutex
    val gattTxChannel = Channel<String>(capacity = 30, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // 1-slot latest-frame buffer for VOICE packets over BLE GATT 0000CAFF
    val bleVoiceTxChannel = Channel<String>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var gattTxConsumerJob: Job? = null
    private val clientDescriptorAckMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val clientCharWriteAckMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile
    var isBleVoiceSendingNow: Boolean = false
        private set

    var onRawJsonPacketReceived: ((String) -> Unit)? = null
    var onGattPeerConnectedForKeyExchange: ((peerNodeId: String?) -> Unit)? = null
    var onPeerBleDiscovered: ((peerNodeId: String, callsign: String, rssi: Int, isHotspotHost: Boolean) -> Unit)? = null
    var onImmediatePeerRegistered: ((
        nodeId: String,
        callsign: String,
        lat: Double?,
        lng: Double?,
        battery: Int?,
        rssi: Int,
        isHotspotHost: Boolean,
        transport: String
    ) -> Unit)? = null
    var getPendingDtnJsonForPeer: ((peerNodeId: String) -> List<String>)? = null

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var extendedAdvSetCallback: Any? = null
    private var codedPhyStaggerJob: Job? = null
    private var shortChatScanRespJob: Job? = null

    private var periodicBeaconJob: Job? = null
    private var scannerRestartLoopJob: Job? = null
    private var bondedDevicesJob: Job? = null
    private var isRunning = false
    private var isAdvertiserStarted = false
    private var beaconCycleCounter = 0

    @Volatile
    var localHotspotPassphrase: String = ""

    @Volatile
    var localHotspotSsid: String = ""

    var onPeerHotspotPassphraseDiscovered: ((nodeIdHex: String, ssidHint: String, passphrase: String) -> Unit)? = null

    @Volatile
    var currentCallStateByte: Int = CALL_STATE_BYTE_IDLE
        private set

    @Volatile
    var currentCallTargetNodeId: String = "ALL"
        private set

    private var callEndedResetJob: Job? = null
    private val lastPeerCallStateByte = ConcurrentHashMap<String, Int>()
    private val lastPeerCallStateEmitMs = ConcurrentHashMap<String, Long>()

    init {
        startGattTxChannelConsumer()
    }

    /**
     * Clears any queued voice frames from the outgoing BLE queue so `CALL_RING`, `CALL_ACCEPT`,
     * `CALL_END`, or `CHAT` control signals transmit immediately with zero delay.
     */
    fun clearPendingBleVoiceQueue() {
        while (bleVoiceTxChannel.tryReceive().isSuccess) {
            // Drain queued JSON BLE voice frames
        }
    }

    private fun startGattTxChannelConsumer() {
        if (gattTxConsumerJob?.isActive == true) return
        gattTxConsumerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Always prioritize control/chat packets in gattTxChannel before voice frames
                val priorityControlJson = gattTxChannel.tryReceive().getOrNull()
                if (priorityControlJson != null) {
                    l2capTransport.sendJsonLine(priorityControlJson)
                    sendGattPacketSerialized(priorityControlJson)
                    continue
                }
                val voiceJson = bleVoiceTxChannel.tryReceive().getOrNull()
                if (voiceJson != null) {
                    isBleVoiceSendingNow = true
                    try {
                        // Prefer zero-chunk L2CAP CoC stream when connected; fallback to GATT 0xCAFF chunks
                        if (!l2capTransport.sendJsonLine(voiceJson)) {
                            sendGattPacketSerialized(voiceJson)
                        }
                    } finally {
                        isBleVoiceSendingNow = false
                    }
                    continue
                }
                delay(8L)
            }
        }
    }

    private fun refreshGattConnectionTelemetry() {
        val serverList = connectedServerDevices.keys.sorted()
        val clientList = connectedGattClients.keys.sorted().map { addr ->
            val mtu = clientNegotiatedMtuMap[addr] ?: 23
            val ready = if (gattServicesReadyPeers[addr] == true) "READY" else "HANDSHAKE"
            "$addr (MTU:$mtu $ready)"
        }
        val symmetric = serverList.isNotEmpty() && connectedGattClients.isNotEmpty()
        _telemetry.update {
            it.copy(
                connectedServerDeviceAddresses = serverList,
                connectedGattClientAddresses = clientList,
                isTwoWayGattSymmetric = symmetric,
                activePeersCount = maxOf(serverList.size, connectedGattClients.size)
            )
        }
    }

    /**
     * Single persistent AdvertiseCallback instance at the class level so stopAdvertising(mainAdvCallback)
     * always releases the active Android BLE advertiser slot cleanly.
     */
    private val mainAdvCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertiserStarted = true
            _telemetry.update {
                it.copy(
                    isAdvertising = true,
                    bleTxStatus = "BLE TX: ACTIVE (12B 0xCAFE)",
                    statusText = "Broadcasting 12B 0xCAFE (${it.txFramesCount} TX)"
                )
            }
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                isAdvertiserStarted = true
                _telemetry.update {
                    it.copy(
                        isAdvertising = true,
                        bleTxStatus = "BLE TX: ACTIVE (12B 0xCAFE)"
                    )
                }
                return
            }
            isAdvertiserStarted = false
            val errDesc = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA TOO LARGE (1)"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO MANY ADVERTISERS (2)"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL ERROR (4)"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "UNSUPPORTED (5)"
                else -> "CODE $errorCode"
            }
            _telemetry.update {
                it.copy(
                    isAdvertising = false,
                    bleTxStatus = "BLE TX ERROR: $errDesc",
                    statusText = "Adv Error: $errDesc"
                )
            }
        }
    }

    /**
     * Updates the 12-byte 0xCAFE AdvertiseData beacon and 12-byte Callsign in 0xCAFF ScanResponse.
     */
    @SuppressLint("MissingPermission")
    suspend fun updateBleBroadcast(advBytes12: ByteArray, scanRespBytes12: ByteArray) {
        if (!isBleHardwareReady()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !checkBtPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            return
        }
        val safeAdvBytes = if (advBytes12.size > 12) advBytes12.copyOf(12) else advBytes12
        val safeScanRespBytes = if (scanRespBytes12.size > 12) scanRespBytes12.copyOf(12) else scanRespBytes12

        advMutex.withLock {
            runCatching {
                bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return@runCatching
                runCatching {
                    bleAdvertiser?.stopAdvertising(mainAdvCallback)
                }
                delay(70L)

                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()

                val advData = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addManufacturerData(MANUFACTURER_ID_CAFE, safeAdvBytes)
                    .build()

                val scanRespData = if (safeScanRespBytes.isNotEmpty()) {
                    AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addManufacturerData(MANUFACTURER_ID_CAFF, safeScanRespBytes)
                        .build()
                } else {
                    null
                }

                if (scanRespData != null) {
                    bleAdvertiser?.startAdvertising(settings, advData, scanRespData, mainAdvCallback)
                } else {
                    bleAdvertiser?.startAdvertising(settings, advData, mainAdvCallback)
                }

                _telemetry.update { it.copy(txFramesCount = it.txFramesCount + 1) }

                // Secondary Long-Range PHY_LE_CODED Advertiser (Staggered 800ms after Primary 1M succeeds & Non-Blocking)
                codedPhyStaggerJob?.cancel()
                codedPhyStaggerJob = scope.launch(Dispatchers.IO) {
                    delay(800L)
                    if (isRunning && isAdvertiserStarted) {
                        startOptionalCodedPhyExtendedBroadcast(safeAdvBytes, safeScanRespBytes)
                    }
                }
            }
        }
    }

    /**
     * Briefly broadcasts a short chat burst inside the 12-byte 0xCAFF ScanResponse (`M:<text>`)
     * for 1,200ms as an instant connectionless backup alongside BLE GATT + UDP/TCP.
     */
    @SuppressLint("MissingPermission")
    fun broadcastShortChatInScanResponse(shortText: String) {
        if (shortText.isBlank() || !isBleHardwareReady()) return
        shortChatScanRespJob?.cancel()
        shortChatScanRespJob = scope.launch(Dispatchers.IO) {
            val (rawLat, rawLng) = getCurrentLocation()
            val (lat, lng) = applyLocationShareMode(rawLat, rawLng, getLocationShareMode())
            val hostBit = if (getHotspotRoleByte() != 0) 0x01 else 0x00
            val combinedRoleByte = hostBit or ((currentCallStateByte and 0x03) shl 1)
            val adv12 = pack12ByteCafeBeacon(
                nodeIdHex = localNodeId(),
                batteryPercent = getBatteryPercent(),
                hotspotRoleByte = combinedRoleByte,
                lat = lat,
                lon = lng
            )
            val taggedBytes = ("M:" + shortText.trim()).toByteArray(Charsets.UTF_8).let {
                if (it.size > 12) it.copyOf(12) else it
            }
            updateBleBroadcast(adv12, taggedBytes)
            delay(1_200L)
            val csBytes = localCallsign().take(12).toByteArray(Charsets.UTF_8)
            updateBleBroadcast(adv12, csBytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOptionalCodedPhyExtendedBroadcast(advBytes12: ByteArray, scanRespBytes12: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val adapter = bluetoothAdapter ?: return
        val adv = bleAdvertiser ?: return
        runCatching {
            if (!adapter.isLeExtendedAdvertisingSupported || !adapter.isLeCodedPhySupported) return@runCatching
            (extendedAdvSetCallback as? AdvertisingSetCallback)?.let { prevCb ->
                runCatching { adv.stopAdvertisingSet(prevCb) }
            }
            val setParams = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val extBuilder = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MANUFACTURER_ID_CAFE, advBytes12)
            if (scanRespBytes12.isNotEmpty()) {
                extBuilder.addManufacturerData(MANUFACTURER_ID_CAFF, scanRespBytes12)
            }
            val setCb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int
                ) {
                    if (status == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS || status != ADVERTISE_SUCCESS) {
                        // Keep the primary 1M advertiser running untouched
                        extendedAdvSetCallback = null
                    }
                }
            }
            extendedAdvSetCallback = setCb
            adv.startAdvertisingSet(setParams, extBuilder.build(), null, null, null, setCb)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            handleRawScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleRawScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _telemetry.update {
                it.copy(isScanning = false, statusText = "Scan failed ($errorCode)")
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        initGattServer()
        l2capTransport.startServer()
        startScannerRestartLoop()
        startPeriodicBeacon()
        startBondedDevicesProbe()
    }

    fun stop() {
        isRunning = false
        isAdvertiserStarted = false
        periodicBeaconJob?.cancel()
        scannerRestartLoopJob?.cancel()
        bondedDevicesJob?.cancel()
        codedPhyStaggerJob?.cancel()
        codedPhyStaggerJob = null
        shortChatScanRespJob?.cancel()
        shortChatScanRespJob = null
        callEndedResetJob?.cancel()
        callEndedResetJob = null

        stopOffGridScanner()
        stopAdvertising()
        l2capTransport.stop()

        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        for (gatt in connectedGattClients.values) {
            try { gatt.close() } catch (_: Exception) {}
        }
        connectedGattClients.clear()
        connectedGattPeers.clear()
        connectedServerDevices.clear()
        pendingGattConnects.clear()
        gattServicesReadyPeers.clear()
        clientNegotiatedMtuMap.clear()
        gattRxStringBuilders.clear()
        clientDescriptorAckMap.clear()
        clientCharWriteAckMap.clear()
        refreshGattConnectionTelemetry()
    }

    private fun checkBtPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isBleHardwareReady(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return false
        }
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        try {
            if (adapter.state != BluetoothAdapter.STATE_ON) return false
        } catch (_: Exception) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkBtPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                return false
            }
        }
        return true
    }

    fun initIfReady() {
        if (isRunning && isBleHardwareReady()) {
            initGattServer()
            l2capTransport.startServer()
            startScannerRestartLoop()
            startBondedDevicesProbe()
        }
    }

    @SuppressLint("MissingPermission")
    fun forceTwoWayGattReDiscovery() {
        scope.launch(Dispatchers.IO) {
            pendingGattConnects.clear()
            if (isBleHardwareReady()) {
                stopOffGridScanner()
                delay(120L)
                startOffGridScanner()
            }

            val allKnownDevices = LinkedHashMap<String, BluetoothDevice>()
            for ((addr, dev) in connectedServerDevices) {
                allKnownDevices[addr] = dev
            }
            for ((addr, dev) in discoveredDevices) {
                allKnownDevices[addr] = dev
            }
            runCatching {
                bluetoothAdapter?.bondedDevices?.forEach { dev ->
                    if (dev.address != null) {
                        allKnownDevices[dev.address] = dev
                    }
                }
            }

            for ((addr, dev) in allKnownDevices) {
                val existingClient = connectedGattClients[addr]
                if (existingClient != null) {
                    if (gattServicesReadyPeers[addr] != true) {
                        runCatching {
                            existingClient.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            val req = existingClient.requestMtu(256)
                            if (!req) existingClient.discoverServices()
                        }
                    }
                } else {
                    triggerGattReverseHandshake(dev, peerNodeIdByMac[addr])
                }
            }

            refreshGattConnectionTelemetry()
        }
    }

    @SuppressLint("MissingPermission")
    fun initGattServer() {
        if (gattServer != null || !isBleHardwareReady()) return

        runCatching {
            val serverCallback = object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
                        connectedServerDevices[device.address] = device
                        discoveredDevices[device.address] = device
                        refreshGattConnectionTelemetry()
                        if (!connectedGattClients.containsKey(device.address)) {
                            triggerGattReverseHandshake(device, peerNodeIdByMac[device.address])
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                        connectedServerDevices.remove(device.address)
                        refreshGattConnectionTelemetry()
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor?,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    if (device != null) {
                        connectedServerDevices[device.address] = device
                        discoveredDevices[device.address] = device
                        refreshGattConnectionTelemetry()
                        val peerIdHint = peerNodeIdByMac[device.address]
                        onGattPeerConnectedForKeyExchange?.invoke(peerIdHint)
                        notifyServerDeviceDtnIfPending(device, peerIdHint)
                        if (!connectedGattClients.containsKey(device.address)) {
                            triggerGattReverseHandshake(device, peerIdHint)
                        }
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic?,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    if (device != null) {
                        connectedServerDevices[device.address] = device
                        discoveredDevices[device.address] = device
                        refreshGattConnectionTelemetry()
                    }
                    if (characteristic?.uuid == VOICE_CHAR_UUID && value != null) {
                        handleIncomingGattPayload(value, device)
                        if (device != null && !connectedGattClients.containsKey(device.address)) {
                            triggerGattReverseHandshake(device, peerNodeIdByMac[device.address])
                        }
                    }
                }

                override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                    super.onMtuChanged(device, mtu)
                    if (device != null) {
                        clientNegotiatedMtuMap[device.address] = mtu
                    }
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    runCatching {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            ByteArray(0)
                        )
                    }
                    if (device != null) {
                        connectedServerDevices[device.address] = device
                        discoveredDevices[device.address] = device
                        refreshGattConnectionTelemetry()
                        if (!connectedGattClients.containsKey(device.address)) {
                            triggerGattReverseHandshake(device, peerNodeIdByMac[device.address])
                        }
                    }
                }
            }

            val server = bluetoothManager?.openGattServer(context, serverCallback) ?: return@runCatching
            gattServer = server
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val charCAFF = BluetoothGattCharacteristic(
                VOICE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccdDescriptor = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            charCAFF.addDescriptor(cccdDescriptor)
            service.addCharacteristic(charCAFF)

            server.addService(service)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScannerRestartLoop() {
        scannerRestartLoopJob?.cancel()
        scannerRestartLoopJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                if (isBleHardwareReady()) {
                    startOffGridScanner()
                }
                // Restart BLE scan cleanly every 25 seconds (stop -> 100ms delay -> start) so Android 12-17 never throttles
                delay(25_000L)
                if (isActive && isRunning && isBleHardwareReady()) {
                    stopOffGridScanner()
                    delay(100L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startOffGridScanner() {
        if (!isBleHardwareReady()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !checkBtPermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }

        runCatching {
            bleScanner = bluetoothAdapter?.bluetoothLeScanner
            bleScanner?.stopScan(scanCallback)

            val standardSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()

            var scanStarted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bluetoothAdapter?.isLeExtendedAdvertisingSupported == true
            ) {
                try {
                    val extSettings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .setLegacy(false)
                        .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                        .build()
                    bleScanner?.startScan(emptyList<ScanFilter>(), extSettings, scanCallback)
                    scanStarted = true
                } catch (_: Throwable) {
                    scanStarted = false
                }
            }

            if (!scanStarted) {
                bleScanner?.startScan(emptyList<ScanFilter>(), standardSettings, scanCallback)
            }

            _telemetry.update { it.copy(isScanning = true, statusText = "Active BLE Scanning (LOW_LATENCY 1M + Coded PHY)") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopOffGridScanner() {
        runCatching {
            bleScanner?.stopScan(scanCallback)
            _telemetry.update { it.copy(isScanning = false) }
        }
    }

    /**
     * Broadcasts a unified JSON MeshPacket across bidirectional BLE GATT (`0000CAFF`).
     * NEVER sends `HEARTBEAT` or `GPS_BEACON` over BLE GATT (`0000CAFF`)!
     */
    @SuppressLint("MissingPermission")
    fun broadcastPacketChunked(packet: WireMeshPacket) {
        scope.launch(Dispatchers.IO) {
            if (packet.type == "CALL_RING" ||
                packet.type == "CALL_ACCEPT" ||
                packet.type == "CALL_ACCEPT_ACK" ||
                packet.type == "CALL_END" ||
                packet.type == "CALL_DECLINE"
            ) {
                clearPendingBleVoiceQueue()
            }
            if (isEligibleForBleGattPipe(packet.type)) {
                val pktJson = packetToJson(packet)
                sendGattPacketChunked(pktJson)
            } else {
                startIdleCafeAdvertising(packet)
            }
        }
    }

    /**
     * Queues a voice frame onto the 1-slot latest-frame `bleVoiceTxChannel` (`capacity = 1`, `DROP_OLDEST`)
     * for BLE GATT (`0000CAFF`) when Hotspot is not yet connected.
     * If `gattSendMutex.isLocked` is true when a new `VOICE` frame is ready, it replaces only the single
     * pending outgoing `VOICE` frame so voice stays real-time with zero queue lag!
     */
    fun sendVoiceFrameIfBleIdle(packet: WireMeshPacket) {
        val jsonStr = packetToJson(packet)
        startGattTxChannelConsumer()
        bleVoiceTxChannel.trySend(jsonStr)
    }

    /**
     * Updates the call state + hotspot host bit inside Byte 5 (`hotspotRoleByte`) of the 12-byte `0xCAFE` BLE beacon:
     * - Bit 0 (0x01): isHotspotHost (1 = hosting Wi-Fi Direct / Local Hotspot)
     * - Bits 1..2 (0x06): callStateByte (0..3)
     * - Bit 7 (0x80): isSos
     */
    @SuppressLint("MissingPermission")
    fun updateCallStateBeacon(callStateByte: Int, targetNodeId: String = "ALL") {
        callEndedResetJob?.cancel()
        currentCallStateByte = callStateByte and 0x03
        currentCallTargetNodeId = targetNodeId.ifBlank { "ALL" }

        scope.launch(Dispatchers.IO) {
            val (rawLat, rawLng) = getCurrentLocation()
            val (lat, lng) = applyLocationShareMode(rawLat, rawLng, getLocationShareMode())
            val hostBit = if (getHotspotRoleByte() != 0) 0x01 else 0x00
            val combinedRoleByte = hostBit or ((currentCallStateByte and 0x03) shl 1)
            val adv12 = pack12ByteCafeBeacon(
                nodeIdHex = localNodeId(),
                batteryPercent = getBatteryPercent(),
                hotspotRoleByte = combinedRoleByte,
                lat = lat,
                lon = lng
            )
            val csBytes = localCallsign().take(12).toByteArray(Charsets.UTF_8)
            updateBleBroadcast(adv12, csBytes)
        }

        if (currentCallStateByte == CALL_STATE_BYTE_CALL_ENDED) {
            callEndedResetJob = scope.launch(Dispatchers.IO) {
                delay(2000L)
                if (currentCallStateByte == CALL_STATE_BYTE_CALL_ENDED) {
                    currentCallStateByte = CALL_STATE_BYTE_IDLE
                    currentCallTargetNodeId = "ALL"
                    val (curLat, curLng) = getCurrentLocation()
                    startIdleCafeAdvertising(
                        WireMeshPacket(
                            packetId = "idle_${System.currentTimeMillis()}",
                            srcNodeId = localNodeId(),
                            srcCallsign = localCallsign(),
                            dstNodeId = "ALL",
                            type = "HEARTBEAT",
                            payload = "Idle Beacon",
                            lat = curLat,
                            lng = curLng,
                            battery = getBatteryPercent()
                        )
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startIdleCafeAdvertising(packet: WireMeshPacket) {
        val isSosBit = if (packet.type == "SOS") 0x80 else 0x00
        val hostBit = if (getHotspotRoleByte() != 0 || localHotspotPassphrase.isNotBlank()) 0x01 else 0x00
        val combinedRoleByte = hostBit or ((currentCallStateByte and 0x03) shl 1) or isSosBit
        val (modeLat, modeLon) = applyLocationShareMode(
            packet.lat,
            packet.lng,
            getLocationShareMode()
        )
        val payload12Bytes = pack12ByteCafeBeacon(
            nodeIdHex = packet.srcNodeId.ifBlank { localNodeId() },
            batteryPercent = packet.battery ?: getBatteryPercent(),
            hotspotRoleByte = combinedRoleByte,
            lat = modeLat,
            lon = modeLon
        )
        beaconCycleCounter++
        val scanRespStr = when {
            localHotspotSsid.isNotBlank() && localHotspotPassphrase.isNotBlank() && (beaconCycleCounter % 3 == 1) ->
                "S:${localHotspotSsid.take(10)}"
            localHotspotPassphrase.isNotBlank() && (beaconCycleCounter % 3 == 2) ->
                "H:${localHotspotPassphrase.take(10)}"
            localHotspotPassphrase.isNotBlank() && localHotspotSsid.isBlank() && (beaconCycleCounter % 2 == 1) ->
                "H:${localHotspotPassphrase.take(10)}"
            else ->
                packet.srcCallsign.ifBlank { localCallsign() }.take(12)
        }
        val csBytes = scanRespStr.toByteArray(Charsets.UTF_8).let {
            if (it.size > 12) it.copyOf(12) else it
        }
        scope.launch(Dispatchers.IO) {
            updateBleBroadcast(payload12Bytes, csBytes)
        }
    }

    fun packetToJson(packet: WireMeshPacket): String {
        return JSONObject().apply {
            put("id", packet.packetId)
            put("type", packet.type)
            put("srcId", packet.srcNodeId)
            put("srcName", packet.srcCallsign.take(12))
            put("dstId", packet.dstNodeId)
            put("ttl", packet.ttl)
            put("hops", packet.hops)
            put("payload", packet.payload)
            packet.lat?.let { put("lat", it) }
            packet.lng?.let { put("lng", it) }
            packet.accuracy?.let { put("acc", it.toDouble()) }
            packet.battery?.let { put("bat", it) }
            if (packet.groupId.isNotBlank()) put("groupId", packet.groupId)
            if (packet.groupName.isNotBlank()) put("groupName", packet.groupName)
            if (packet.memberIdsCsv.isNotBlank()) put("memberIds", packet.memberIdsCsv)
            if (packet.senderPubKey.isNotBlank()) put("pk", packet.senderPubKey)
            if (packet.encryptedKeysJson.isNotBlank()) put("ekeys", packet.encryptedKeysJson)
            if (packet.srcUsername.isNotBlank()) put("su", packet.srcUsername)
            if (packet.dstUsername.isNotBlank()) put("du", packet.dstUsername)
            if (packet.isGateway) put("gw", true)
            if (packet.type != "VOICE" && l2capTransport.localPsm > 0) {
                put("l2psm", l2capTransport.localPsm)
            }
            put("ts", packet.timestamp)
        }.toString()
    }

    fun handleCafeBeaconBytes(
        mfgCafe: ByteArray,
        mfgCaff: ByteArray?,
        rssi: Int,
        device: BluetoothDevice? = null,
        blePhy: String = "BLE 1M"
    ) {
        if (mfgCafe.size < 12) return
        val decoded = unpackCafePayload(mfgCafe) ?: return
        if (decoded.nodeIdHex.isBlank() || decoded.nodeIdHex == "0x0000" || normalizeId(decoded.nodeIdHex) == normalizeId(localNodeId())) {
            return
        }

        val normPeerId = normalizeId(decoded.nodeIdHex)
        peerLastRssi[normPeerId] = rssi
        peerLastRssi[decoded.nodeIdHex] = rssi
        peerLastBlePhy[normPeerId] = blePhy
        peerLastBlePhy[decoded.nodeIdHex] = blePhy
        if (device != null && device.address != null) {
            peerMacByNodeId[normPeerId] = device.address
            peerMacByNodeId[decoded.nodeIdHex] = device.address
            peerNodeIdByMac[device.address] = normPeerId
            peerLastRssiByMac[device.address] = rssi
        }

        var shortChatFromScanResp: String? = null
        if (mfgCaff != null && mfgCaff.isNotEmpty()) {
            val scannedStr = String(mfgCaff, Charsets.UTF_8).trim()
            if (scannedStr.startsWith("M:") && scannedStr.length > 2) {
                shortChatFromScanResp = scannedStr.removePrefix("M:").trim()
            } else if (scannedStr.startsWith("S:") && scannedStr.length > 2) {
                val ssidPart = scannedStr.removePrefix("S:").trim()
                if (ssidPart.isNotBlank()) {
                    knownPeerHotspotSsids[decoded.nodeIdHex] = ssidPart
                }
            } else if (scannedStr.startsWith("H:") && scannedStr.length > 2) {
                val passPart = scannedStr.removePrefix("H:").trim()
                if (passPart.isNotBlank()) {
                    val ssidHint = knownPeerHotspotSsids[decoded.nodeIdHex].orEmpty()
                    onPeerHotspotPassphraseDiscovered?.invoke(decoded.nodeIdHex, ssidHint, passPart)
                }
            } else if (scannedStr.any { it.isLetterOrDigit() } && !isBlockedFakeCallsign(scannedStr)) {
                knownPeerCallsigns[decoded.nodeIdHex] = scannedStr
            }
        }

        _telemetry.update {
            it.copy(
                rxFramesCount = it.rxFramesCount + 1,
                lastRssiDbm = rssi
            )
        }

        val callsignStr = knownPeerCallsigns[decoded.nodeIdHex]
            ?: if (decoded.callsignPrefix.any { it.isLetterOrDigit() } && decoded.callsignPrefix != "PM") {
                decoded.callsignPrefix
            } else {
                "Node-${decoded.nodeIdHex}"
            }
        if (isBlockedFakeCallsign(callsignStr)) return

        peerIsHotspotHost[decoded.nodeIdHex] = decoded.isHotspotHost
        val now = System.currentTimeMillis()

        // Rule #1: IMMEDIATELY register/update peer inside activePeers BEFORE attempting connectGatt or any socket handshake!
        onImmediatePeerRegistered?.invoke(
            decoded.nodeIdHex,
            callsignStr,
            decoded.latitude,
            decoded.longitude,
            decoded.batteryPercent,
            rssi,
            decoded.isHotspotHost,
            blePhy
        )

        if (!shortChatFromScanResp.isNullOrBlank()) {
            val shortChatDedupId = "caff_chat_${decoded.nodeIdHex}_${shortChatFromScanResp.hashCode()}"
            val prevSeen = seenPacketIds[shortChatDedupId] ?: 0L
            if (now - prevSeen > 5_000L) {
                seenPacketIds[shortChatDedupId] = now
                val shortChatPkt = WireMeshPacket(
                    packetId = shortChatDedupId,
                    srcNodeId = decoded.nodeIdHex,
                    srcCallsign = callsignStr,
                    dstNodeId = "ALL",
                    type = "CHAT",
                    payload = shortChatFromScanResp,
                    lat = decoded.latitude,
                    lng = decoded.longitude,
                    battery = decoded.batteryPercent,
                    timestamp = now,
                    sourceTransport = blePhy,
                    rssiDbm = rssi
                )
                _incomingPackets.tryEmit(shortChatPkt)
            }
        }

        val packet = WireMeshPacket(
            packetId = "ble_${decoded.nodeIdHex}_$now",
            srcNodeId = decoded.nodeIdHex,
            srcCallsign = callsignStr,
            dstNodeId = "BROADCAST",
            type = if (decoded.isSos) "SOS" else if (decoded.latitude != null && decoded.longitude != null) "GPS_BEACON" else "HEARTBEAT",
            payload = if (decoded.isHotspotHost) "HOTSPOT_HOST_ACTIVE" else "Offline BLE 0xCAFE Frame",
            lat = decoded.latitude,
            lng = decoded.longitude,
            battery = decoded.batteryPercent,
            timestamp = now,
            sourceTransport = blePhy,
            rssiDbm = rssi
        )
        _incomingPackets.tryEmit(packet)
        onPeerBleDiscovered?.invoke(decoded.nodeIdHex, callsignStr, rssi, decoded.isHotspotHost)

        // Check callStateByte inside the 12-byte 0xCAFE beacon
        val prevState = lastPeerCallStateByte[decoded.nodeIdHex] ?: CALL_STATE_BYTE_IDLE
        val lastEmit = lastPeerCallStateEmitMs[decoded.nodeIdHex] ?: 0L
        lastPeerCallStateByte[decoded.nodeIdHex] = decoded.callStateByte

        val isTransition = decoded.callStateByte != prevState
        val shouldEmitCafeSignal = when (decoded.callStateByte) {
            CALL_STATE_BYTE_RINGING_OUTGOING -> isTransition || (now - lastEmit >= 2200L)
            // Re-emit IN_CALL_ACTIVE every 1.4s so if the initial CALL_ACCEPT packet is missed,
            // the caller's BLE scanner still transitions RINGING_OUTGOING -> IN_CALL_ACTIVE reliably!
            CALL_STATE_BYTE_IN_CALL_ACTIVE -> isTransition || (now - lastEmit >= 1400L)
            CALL_STATE_BYTE_CALL_ENDED -> isTransition
            else -> false
        }

        if (shouldEmitCafeSignal) {
            val callSignalType = when (decoded.callStateByte) {
                CALL_STATE_BYTE_RINGING_OUTGOING -> "CALL_RING"
                CALL_STATE_BYTE_IN_CALL_ACTIVE -> "CALL_ACCEPT"
                CALL_STATE_BYTE_CALL_ENDED -> "CALL_END"
                else -> null
            }
            if (callSignalType != null) {
                lastPeerCallStateEmitMs[decoded.nodeIdHex] = now
                val callSignalPkt = WireMeshPacket(
                    packetId = "cafe_call_${decoded.nodeIdHex}_${decoded.callStateByte}_$now",
                    srcNodeId = decoded.nodeIdHex,
                    srcCallsign = callsignStr,
                    dstNodeId = "ALL",
                    type = callSignalType,
                    payload = callsignStr,
                    lat = decoded.latitude,
                    lng = decoded.longitude,
                    battery = decoded.batteryPercent,
                    timestamp = now,
                    sourceTransport = blePhy,
                    rssiDbm = rssi
                )
                if (!_incomingPackets.tryEmit(callSignalPkt)) {
                    scope.launch { _incomingPackets.emit(callSignalPkt) }
                }
            }
        }

        if (device != null) {
            discoveredDevices[device.address] = device
            if (!connectedGattClients.containsKey(device.address) && !connectedServerDevices.containsKey(device.address)) {
                triggerGattReverseHandshake(device, decoded.nodeIdHex)
            }
        }
    }

    private fun handleRawScanResult(result: ScanResult) {
        val device = result.device
        val scanRecord = result.scanRecord ?: return
        val mfgCafe = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID_CAFE) ?: return
        val mfgCaff = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID_CAFF)
        val realRssi = result.rssi
        val blePhy = if (Build.VERSION.SDK_INT >= 26 && result.primaryPhy == BluetoothDevice.PHY_LE_CODED) {
            "BLE CODED"
        } else {
            "BLE 1M"
        }
        handleCafeBeaconBytes(mfgCafe, mfgCaff, realRssi, device, blePhy)
    }

    @SuppressLint("MissingPermission")
    private fun notifyServerDeviceDtnIfPending(device: BluetoothDevice, peerNodeIdHint: String?) {
        scope.launch(Dispatchers.IO) {
            val peerId = peerNodeIdHint ?: peerNodeIdByMac[device.address] ?: ""
            if (peerId.isBlank()) return@launch
            val dtnJsons = getPendingDtnJsonForPeer?.invoke(peerId).orEmpty()
            for (dtnJson in dtnJsons) {
                sendGattPacketChunked(dtnJson)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerGattReverseHandshake(device: BluetoothDevice, peerNodeIdHint: String? = null) {
        val addr = device.address ?: return
        if (connectedGattClients.containsKey(addr) || !pendingGattConnects.add(addr)) return

        scope.launch(Dispatchers.IO) {
            scope.launch(Dispatchers.IO) {
                delay(5000L)
                pendingGattConnects.remove(addr)
            }

            val peerIdHex = peerNodeIdHint ?: peerNodeIdByMac[addr] ?: ""
            val staggerDelayMs = if (peerIdHex.isNotBlank()) {
                computeGattRoleStaggerDelayMs(localNodeId(), peerIdHex)
            } else {
                150L
            }

            delay(staggerDelayMs)
            // If myNodeId < peerNodeId (1500ms wait), only call connectGatt if that peer has not already connected to our BluetoothGattServer!
            if (staggerDelayMs >= 1500L && (connectedServerDevices.containsKey(addr) || connectedGattClients.containsKey(addr))) {
                pendingGattConnects.remove(addr)
                return@launch
            }
            if (connectedGattClients.containsKey(addr)) {
                pendingGattConnects.remove(addr)
                return@launch
            }

            // N-Node BLE GATT Client Pool Ceiling (Android BT hardware cap ~7 simultaneous outgoing links):
            // Prioritize discovered peers NOT already in connectedServerDevices; rotate stalest if full.
            if (connectedGattClients.size >= MAX_OUTGOING_GATT_CLIENTS) {
                val evictCandidate = connectedGattClients.keys
                    .sortedBy { gattClientLastActivityMs[it] ?: 0L }
                    .firstOrNull { connectedServerDevices.containsKey(it) }
                    ?: connectedGattClients.keys.minByOrNull { gattClientLastActivityMs[it] ?: 0L }

                if (evictCandidate != null && connectedServerDevices.containsKey(addr)) {
                    pendingGattConnects.remove(addr)
                    return@launch
                } else if (evictCandidate != null) {
                    runCatching { connectedGattClients.remove(evictCandidate)?.close() }
                    connectedGattPeers.remove(evictCandidate)
                    gattServicesReadyPeers.remove(evictCandidate)
                    clientNegotiatedMtuMap.remove(evictCandidate)
                    gattClientLastActivityMs.remove(evictCandidate)
                }
            }

            runCatching {
                val gattCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                        pendingGattConnects.remove(addr)
                        if (newState == BluetoothProfile.STATE_CONNECTED && gatt != null) {
                            connectedGattPeers[addr] = gatt
                            connectedGattClients[addr] = gatt
                            gattClientLastActivityMs[addr] = System.currentTimeMillis()
                            clientNegotiatedMtuMap.putIfAbsent(addr, 23)
                            refreshGattConnectionTelemetry()
                            runCatching {
                                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    bluetoothAdapter?.isLe2MPhySupported == true
                                ) {
                                    gatt.setPreferredPhy(
                                        BluetoothDevice.PHY_LE_2M_MASK,
                                        BluetoothDevice.PHY_LE_2M_MASK,
                                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                                    )
                                }
                            }
                            scope.launch(Dispatchers.IO) {
                                delay(200L)
                                runCatching {
                                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                        bluetoothAdapter?.isLe2MPhySupported == true
                                    ) {
                                        gatt.setPreferredPhy(
                                            BluetoothDevice.PHY_LE_2M_MASK,
                                            BluetoothDevice.PHY_LE_2M_MASK,
                                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                                        )
                                    }
                                    val mtuRequested = gatt.requestMtu(256)
                                    if (!mtuRequested) {
                                        gatt.discoverServices()
                                    }
                                }
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            connectedGattPeers.remove(addr)
                            connectedGattClients.remove(addr)
                            gattServicesReadyPeers.remove(addr)
                            clientNegotiatedMtuMap.remove(addr)
                            clientDescriptorAckMap.remove(addr)?.complete(false)
                            clientCharWriteAckMap.remove(addr)?.complete(false)
                            refreshGattConnectionTelemetry()
                            runCatching { gatt?.close() }
                            scope.launch(Dispatchers.IO) {
                                delay(2000)
                                if (isRunning && !connectedGattClients.containsKey(addr) && !pendingGattConnects.contains(addr)) {
                                    triggerGattReverseHandshake(device, peerNodeIdByMac[addr])
                                }
                            }
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                        val negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                        clientNegotiatedMtuMap[addr] = negotiatedMtu
                        refreshGattConnectionTelemetry()
                        runCatching { gatt?.discoverServices() }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                            runCatching {
                                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            }
                            val service = gatt.getService(MESH_SERVICE_UUID)
                            val charCAFF = service?.getCharacteristic(VOICE_CHAR_UUID)
                            if (charCAFF != null) {
                                scope.launch(Dispatchers.IO) {
                                    suspend fun enableCccd(ch: BluetoothGattCharacteristic) {
                                        gatt.setCharacteristicNotification(ch, true)
                                        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
                                        val descAck = CompletableDeferred<Boolean>()
                                        clientDescriptorAckMap[addr] = descAck
                                        val wrote = runCatching {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                gatt.writeDescriptor(
                                                    cccd,
                                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                                ) == BluetoothStatusCodes.SUCCESS
                                            } else {
                                                @Suppress("DEPRECATION")
                                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                                @Suppress("DEPRECATION")
                                                gatt.writeDescriptor(cccd)
                                            }
                                        }.getOrDefault(false)
                                        if (wrote) {
                                            withTimeoutOrNull(300L) { descAck.await() }
                                        } else {
                                            delay(200L)
                                        }
                                        clientDescriptorAckMap.remove(addr)
                                    }

                                    enableCccd(charCAFF)
                                    gattServicesReadyPeers[addr] = true
                                    refreshGattConnectionTelemetry()
                                    val peerIdHint = peerNodeIdByMac[addr]
                                    onGattPeerConnectedForKeyExchange?.invoke(peerIdHint)
                                    // Do NOT send HEARTBEAT over BLE GATT! Only flush pending DTN messages if any exist.
                                    notifyServerDeviceDtnIfPending(device, peerIdHint)
                                }
                            }
                        }
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt?,
                        descriptor: BluetoothGattDescriptor?,
                        status: Int
                    ) {
                        val devAddr = gatt?.device?.address ?: addr
                        clientDescriptorAckMap.remove(devAddr)?.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt?,
                        characteristic: BluetoothGattCharacteristic?,
                        status: Int
                    ) {
                        val devAddr = gatt?.device?.address ?: addr
                        clientCharWriteAckMap.remove(devAddr)?.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        val payload = characteristic.value ?: return
                        handleIncomingGattPayload(payload, gatt.device)
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        handleIncomingGattPayload(value, gatt.device)
                    }

                    override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                            val devAddr = gatt.device?.address ?: addr
                            peerLastRssiByMac[devAddr] = rssi
                            val normPeer = peerNodeIdByMac[devAddr]
                            if (!normPeer.isNullOrBlank()) {
                                peerLastRssi[normPeer] = rssi
                                peerLastRssi["0x$normPeer"] = rssi
                                val cs = knownPeerCallsigns["0x$normPeer"] ?: ""
                                onImmediatePeerRegistered?.invoke(
                                    "0x$normPeer",
                                    cs,
                                    null,
                                    null,
                                    null,
                                    rssi,
                                    peerIsHotspotHost["0x$normPeer"] == true,
                                    "BLE GATT"
                                )
                            }
                            // Adaptive BLE PHY switching: use LE Coded S=8 at weak signal (< -86 dBm) and LE 2M at strong signal
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                runCatching {
                                    if (rssi < -86 && bluetoothAdapter?.isLeCodedPhySupported == true) {
                                        gatt.setPreferredPhy(
                                            BluetoothDevice.PHY_LE_CODED_MASK,
                                            BluetoothDevice.PHY_LE_CODED_MASK,
                                            BluetoothDevice.PHY_OPTION_S8
                                        )
                                    } else if (rssi > -78 && bluetoothAdapter?.isLe2MPhySupported == true) {
                                        gatt.setPreferredPhy(
                                            BluetoothDevice.PHY_LE_2M_MASK,
                                            BluetoothDevice.PHY_LE_2M_MASK,
                                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                                        )
                                    }
                                }
                            }
                            _telemetry.update { it.copy(lastRssiDbm = rssi) }
                        }
                    }
                }

                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }.onFailure {
                pendingGattConnects.remove(addr)
            }
        }
    }

    /**
     * Strictly Serialized, Callback-Synchronized BLE GATT Chunk Sender:
     * - Protects all BLE GATT writes with `gattSendMutex` so two packets NEVER interleave their chunks.
     * - Frames every JSON packet cleanly as `"\n" + jsonString.trim() + "\n"`.
     * - For `BluetoothGatt` client writes, awaits `CompletableDeferred<Boolean>` from `onCharacteristicWrite`
     *   (with a `100ms` timeout) so chunk `N+1` is only written AFTER Android confirms chunk `N` finished transmitting!
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendGattPacketSerialized(jsonString: String) {
        val cleanJson = jsonString.trim()
        if (cleanJson.isEmpty()) return
        val fullBytes = ("\n" + cleanJson + "\n").toByteArray(Charsets.UTF_8)
        if (fullBytes.isEmpty()) return

        val isVoiceFrame = cleanJson.contains("\"type\":\"VOICE\"") ||
            cleanJson.contains("\"type\":\"VOICE_STREAM\"") ||
            cleanJson.contains("\"type\":\"VOICE_PTT\"") ||
            cleanJson.contains("\"t\":\"VOICE\"")

        val serverNotifyPaceMs = if (isVoiceFrame) 8L else 24L
        val primaryWriteType = if (isVoiceFrame) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val fallbackWriteType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        gattSendMutex.withLock {
            val clientAckedAddresses = HashSet<String>()
            for ((addr, gatt) in connectedGattClients) {
                val service = runCatching { gatt.getService(MESH_SERVICE_UUID) }.getOrNull() ?: continue
                val charCAFF = service.getCharacteristic(VOICE_CHAR_UUID) ?: continue
                val negotiatedMtu = clientNegotiatedMtuMap[addr] ?: 23
                val maxChunkSize = if (negotiatedMtu < 185) 20 else 160

                var offset = 0
                var allChunksAckedForClient = true
                while (offset < fullBytes.size) {
                    val end = minOf(offset + maxChunkSize, fullBytes.size)
                    val slice = fullBytes.copyOfRange(offset, end)
                    val writeAck = CompletableDeferred<Boolean>()
                    clientCharWriteAckMap[addr] = writeAck

                    val wrote = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val res = gatt.writeCharacteristic(
                                charCAFF,
                                slice,
                                primaryWriteType
                            )
                            if (res != BluetoothStatusCodes.SUCCESS) {
                                gatt.writeCharacteristic(
                                    charCAFF,
                                    slice,
                                    fallbackWriteType
                                ) == BluetoothStatusCodes.SUCCESS
                            } else {
                                true
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            charCAFF.value = slice
                            @Suppress("DEPRECATION")
                            charCAFF.writeType = primaryWriteType
                            @Suppress("DEPRECATION")
                            val ok = gatt.writeCharacteristic(charCAFF)
                            if (!ok) {
                                @Suppress("DEPRECATION")
                                charCAFF.writeType = fallbackWriteType
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(charCAFF)
                            } else {
                                true
                            }
                        }
                    }.getOrDefault(false)

                    if (wrote) {
                        _telemetry.update { it.copy(gattAckedChunksCount = it.gattAckedChunksCount + 1) }
                        if (isVoiceFrame) {
                            // Fast non-blocking pace for WRITE_TYPE_NO_RESPONSE voice streaming
                            clientCharWriteAckMap.remove(addr)
                            delay(8L)
                        } else {
                            val ackOk = withTimeoutOrNull(100L) { writeAck.await() } == true
                            if (!ackOk) allChunksAckedForClient = false
                        }
                    } else {
                        allChunksAckedForClient = false
                        clientCharWriteAckMap.remove(addr)
                        delay(18L)
                    }
                    offset = end
                }
                if (allChunksAckedForClient) {
                    clientAckedAddresses.add(addr)
                }
            }

            val srv = gattServer
            val srvService = srv?.getService(MESH_SERVICE_UUID)
            val srvCharCAFF = srvService?.getCharacteristic(VOICE_CHAR_UUID)
            if (srv != null && srvCharCAFF != null) {
                val serverTargets = connectedServerDevices.values
                    .filter { isVoiceFrame || !clientAckedAddresses.contains(it.address) }
                    .distinctBy { it.address }
                for (device in serverTargets) {
                    val addr = device.address ?: continue
                    val negotiatedMtu = clientNegotiatedMtuMap[addr] ?: 23
                    val maxChunkSize = if (negotiatedMtu < 185) 20 else 160
                    var offset = 0
                    while (offset < fullBytes.size) {
                        val end = minOf(offset + maxChunkSize, fullBytes.size)
                        val slice = fullBytes.copyOfRange(offset, end)

                        val notified = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                srv.notifyCharacteristicChanged(device, srvCharCAFF, false, slice) == BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                srvCharCAFF.value = slice
                                @Suppress("DEPRECATION")
                                srv.notifyCharacteristicChanged(device, srvCharCAFF, false)
                            }
                        }.getOrDefault(false)

                        if (notified) {
                            _telemetry.update { it.copy(gattAckedChunksCount = it.gattAckedChunksCount + 1) }
                        }
                        delay(serverNotifyPaceMs)
                        offset = end
                    }
                }
            }
        }
    }

    /**
     * Reassembles newline-delimited (`\n` + JSON + `\n`) packets arriving over BLE GATT `0000CAFF`.
     * Extracts lines between `\n`, trims, and ONLY parses lines that start with `{` and end with `}` inside `runCatching`.
     */
    fun handleIncomingGattPayload(payload: ByteArray, device: BluetoothDevice?) {
        if (payload.isEmpty()) return

        val devAddr = device?.address
        if (device != null && !devAddr.isNullOrBlank()) {
            connectedServerDevices[devAddr] = device
            discoveredDevices[devAddr] = device
            refreshGattConnectionTelemetry()
        }

        val key = devAddr ?: "default"
        val sb = gattRxStringBuilders.getOrPut(key) { StringBuilder() }
        synchronized(sb) {
            val incomingText = String(payload, Charsets.UTF_8)
            sb.append(incomingText)
            var newlineIdx = sb.indexOf("\n")
            while (newlineIdx >= 0) {
                val rawLine = sb.substring(0, newlineIdx).trim()
                sb.delete(0, newlineIdx + 1)
                val line = if (rawLine.startsWith("{")) {
                    rawLine
                } else {
                    val braceIdx = rawLine.indexOf('{')
                    if (braceIdx >= 0) rawLine.substring(braceIdx) else ""
                }
                if (line.startsWith("{") && line.endsWith("}")) {
                    runCatching {
                        val obj = JSONObject(line)
                        val srcId = obj.optString("srcId").ifBlank { obj.optString("src") }
                        val srcName = obj.optString("srcName").ifBlank { obj.optString("cs", "Node-$srcId") }
                        val normSrc = normalizeId(srcId)
                        if (isBlockedFakeCallsign(srcName)) {
                            newlineIdx = sb.indexOf("\n")
                            continue
                        }
                        val realRssi = peerLastRssi[normSrc]
                            ?: peerLastRssi[srcId]
                            ?: device?.address?.let { peerLastRssiByMac[it] }
                            ?: _telemetry.value.lastRssiDbm.takeIf { it != 0 }
                        if (srcId.isNotBlank() && normSrc != normalizeId(localNodeId())) {
                            if (device?.address != null) {
                                peerNodeIdByMac[device.address] = normSrc
                                peerMacByNodeId[normSrc] = device.address
                            }
                            onImmediatePeerRegistered?.invoke(
                                srcId,
                                srcName,
                                if (obj.has("lat") && !obj.isNull("lat")) obj.getDouble("lat") else null,
                                if (obj.has("lng") && !obj.isNull("lng")) obj.getDouble("lng") else null,
                                if (obj.has("bat") && !obj.isNull("bat")) obj.getInt("bat") else null,
                                realRssi ?: -55,
                                false,
                                "BLE GATT"
                            )
                            val remotePsm = obj.optInt("l2psm", 0)
                            if (remotePsm > 0 && device != null) {
                                l2capTransport.connectToPeerIfNeeded(device, remotePsm)
                            }
                        }
                        onRawJsonPacketReceived?.invoke(line)
                        parseAndEmitOffGridJson(line, realRssi, "BLE GATT")
                    }
                }
                newlineIdx = sb.indexOf("\n")
            }
            if (sb.length > 32768) {
                sb.clear()
            }
        }
    }

    private fun handleIncomingL2capLine(line: String, device: BluetoothDevice?) {
        runCatching {
            val obj = JSONObject(line)
            val srcId = obj.optString("srcId").ifBlank { obj.optString("src") }
            val srcName = obj.optString("srcName").ifBlank { obj.optString("cs", "Node-$srcId") }
            val normSrc = normalizeId(srcId)
            if (isBlockedFakeCallsign(srcName)) return
            val realRssi = peerLastRssi[normSrc]
                ?: peerLastRssi[srcId]
                ?: device?.address?.let { peerLastRssiByMac[it] }
                ?: _telemetry.value.lastRssiDbm.takeIf { it != 0 }
            if (srcId.isNotBlank() && normSrc != normalizeId(localNodeId())) {
                if (device?.address != null) {
                    peerNodeIdByMac[device.address] = normSrc
                    peerMacByNodeId[normSrc] = device.address
                }
                onImmediatePeerRegistered?.invoke(
                    srcId,
                    srcName,
                    if (obj.has("lat") && !obj.isNull("lat")) obj.getDouble("lat") else null,
                    if (obj.has("lng") && !obj.isNull("lng")) obj.getDouble("lng") else null,
                    if (obj.has("bat") && !obj.isNull("bat")) obj.getInt("bat") else null,
                    realRssi ?: -55,
                    false,
                    "BLE L2CAP"
                )
            }
            onRawJsonPacketReceived?.invoke(line)
            parseAndEmitOffGridJson(line, realRssi, "BLE L2CAP")
        }
    }

    /**
     * Enqueues user-triggered packets (`CHAT`, `E2EE_DM`, `KEY_ANNOUNCE`, `CHAT_ACK`, `CALL_*`, `WAYPOINT`, `HOTSPOT_*`, `VOICE`)
     * onto BLE GATT (`0000CAFF`). Strictly rejects `HEARTBEAT` and `GPS_BEACON` so BLE GATT is never congested!
     */
    @SuppressLint("MissingPermission")
    fun sendGattPacketChunked(jsonString: String) {
        val trimmed = jsonString.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return
        val pktType = runCatching {
            val obj = JSONObject(trimmed)
            obj.optString("type").ifBlank { obj.optString("t", "") }
        }.getOrDefault("")
        if (!isEligibleForBleGattPipe(pktType)) return

        if (pktType == "CALL_RING" ||
            pktType == "CALL_ACCEPT" ||
            pktType == "CALL_ACCEPT_ACK" ||
            pktType == "CALL_END" ||
            pktType == "CALL_DECLINE" ||
            pktType == "CHAT" ||
            pktType == "E2EE_DM" ||
            pktType == "GROUP_CHAT"
        ) {
            clearPendingBleVoiceQueue()
        }

        startGattTxChannelConsumer()
        if (pktType == "VOICE" || pktType == "VOICE_STREAM" || pktType == "VOICE_PTT") {
            bleVoiceTxChannel.trySend(trimmed)
        } else {
            gattTxChannel.trySend(trimmed)
        }
    }

    private fun parseAndEmitOffGridJson(rawJson: String, rssi: Int?, sourceTransport: String = "BLE GATT") {
        runCatching {
            val root = JSONObject(rawJson)
            val packetId = root.optString("id").ifBlank { "pkt_${System.currentTimeMillis()}" }
            val srcNodeId = root.optString("srcId").ifBlank { root.optString("src") }
            if (srcNodeId.isBlank() || normalizeId(srcNodeId) == normalizeId(localNodeId())) return

            val type = root.optString("type").ifBlank { root.optString("t", "HEARTBEAT") }
            val payloadStr = root.optString("payload").ifBlank { root.optString("p", "") }
            val dstNodeId = root.optString("dstId").ifBlank { root.optString("dst", "ALL") }

            val isCallControl = type == "CALL_RING" ||
                type == "CALL_ACCEPT" ||
                type == "CALL_ACCEPT_ACK" ||
                type == "CALL_END" ||
                type == "CALL_DECLINE" ||
                type == "CHAT_ACK" ||
                type == "HOTSPOT_AUTO_JOIN" ||
                type == "KEY_ANNOUNCE" ||
                type == "VOICE" ||
                type == "VOICE_STREAM" ||
                type == "VOICE_PTT"
            if (type != "HEARTBEAT" && type != "GPS_BEACON" && !isCallControl) {
                if (seenPacketIds.putIfAbsent(packetId, System.currentTimeMillis()) != null) {
                    return
                }
            }

            val cs = root.optString("srcName").ifBlank { root.optString("cs", "Node-$srcNodeId") }
            if (isBlockedFakeCallsign(cs)) return
            if (cs.isNotBlank()) {
                knownPeerCallsigns[srcNodeId] = cs
            }

            val effectiveRssi = rssi ?: peerLastRssi[normalizeId(srcNodeId)] ?: peerLastRssi[srcNodeId]
            val packet = WireMeshPacket(
                packetId = packetId,
                srcNodeId = srcNodeId,
                srcCallsign = cs,
                dstNodeId = dstNodeId,
                type = type,
                payload = payloadStr,
                ttl = root.optInt("ttl", 7),
                hops = root.optInt("hops", 0),
                lat = if (root.has("lat") && !root.isNull("lat")) root.getDouble("lat") else null,
                lng = if (root.has("lng") && !root.isNull("lng")) root.getDouble("lng") else null,
                accuracy = if (root.has("acc") && !root.isNull("acc")) root.getDouble("acc").toFloat() else null,
                battery = if (root.has("bat") && !root.isNull("bat")) root.getInt("bat") else null,
                timestamp = System.currentTimeMillis(),
                sourceTransport = sourceTransport,
                rssiDbm = effectiveRssi,
                groupId = root.optString("groupId", ""),
                groupName = root.optString("groupName", ""),
                memberIdsCsv = root.optString("memberIds", ""),
                senderPubKey = root.optString("pk", "").ifBlank { root.optString("senderPubKey", "") },
                encryptedKeysJson = root.optString("ekeys", "").ifBlank { root.optString("encryptedKeys", "") },
                srcUsername = root.optString("su", ""),
                dstUsername = root.optString("du", ""),
                isGateway = root.optBoolean("gw", false)
            )
            if (effectiveRssi != null) {
                _telemetry.update { it.copy(rxFramesCount = it.rxFramesCount + 1, lastRssiDbm = effectiveRssi) }
            } else {
                _telemetry.update { it.copy(rxFramesCount = it.rxFramesCount + 1) }
            }
            if (!_incomingPackets.tryEmit(packet)) {
                scope.launch { _incomingPackets.emit(packet) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBondedDevicesProbe() {
        bondedDevicesJob?.cancel()
        bondedDevicesJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            while (isActive && isRunning) {
                if (isBleHardwareReady()) {
                    runCatching {
                        val bonded = bluetoothAdapter?.bondedDevices
                        bonded?.forEach { dev ->
                            discoveredDevices[dev.address] = dev
                            if (!connectedGattPeers.containsKey(dev.address)) {
                                triggerGattReverseHandshake(dev, peerNodeIdByMac[dev.address])
                            }
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun startPeriodicBeacon() {
        periodicBeaconJob?.cancel()
        periodicBeaconJob = scope.launch(Dispatchers.IO) {
            delay(300)
            while (isActive && isRunning) {
                if (isBleHardwareReady()) {
                    val (lat, lng) = getCurrentLocation()
                    val beaconPkt = WireMeshPacket(
                        packetId = "ble_hb_${localNodeId()}_${System.currentTimeMillis()}",
                        srcNodeId = localNodeId(),
                        srcCallsign = localCallsign(),
                        dstNodeId = currentCallTargetNodeId,
                        type = if (lat != null && lng != null) "GPS_BEACON" else "HEARTBEAT",
                        payload = "BLE 12B 0xCAFE Beacon",
                        lat = lat,
                        lng = lng,
                        accuracy = getLocationAccuracy(),
                        battery = getBatteryPercent(),
                        timestamp = System.currentTimeMillis()
                    )
                    startIdleCafeAdvertising(beaconPkt)
                }
                delay(2_800L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        runCatching {
            if (isBleHardwareReady()) {
                bleAdvertiser?.stopAdvertising(mainAdvCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    (extendedAdvSetCallback as? AdvertisingSetCallback)?.let { cb ->
                        bleAdvertiser?.stopAdvertisingSet(cb)
                    }
                }
            }
        }
        extendedAdvSetCallback = null
        isAdvertiserStarted = false
        _telemetry.update { it.copy(isAdvertising = false, bleTxStatus = "BLE TX: STOPPED") }
    }
}
