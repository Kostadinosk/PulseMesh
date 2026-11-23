// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class LoraProtocol {
    AUTO,
    MESHTASTIC,
    MESHCORE
}

enum class LoraTransport {
    NONE,
    USB_OTG,
    BLE_GATT
}

data class LoraBridgeState(
    val isEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val protocol: LoraProtocol = LoraProtocol.AUTO,
    val transport: LoraTransport = LoraTransport.NONE,
    val deviceName: String = "",
    val txFrames: Int = 0,
    val rxFrames: Int = 0,
    val lastRssiDbm: Int? = null,
    val lastSnrDb: Float? = null,
    val statusText: String = "Idle (USB-OTG / BLE)"
)

data class LoraDecodedMessage(
    val protocol: LoraProtocol,
    val senderNodeHex: String,
    val text: String,
    val rssiDbm: Int? = null,
    val snrDb: Float? = null
)

/**
 * Minimal pure-Kotlin Protobuf & serial stream codec (`0x94 0xC3` framing) for Meshtastic radios.
 */
object MeshtasticWireCodec {

    private const val SYNC_0: Byte = 0x94.toByte()
    private const val SYNC_1: Byte = 0xC3.toByte()
    private const val MAX_PROTOBUF_BYTES = 512

    const val PORTNUM_TEXT_MESSAGE_APP = 1
    const val PORTNUM_PRIVATE_APP = 256

    private val packetIdSeq = AtomicInteger(0x1000)

    private fun writeVarint(out: ByteArrayOutputStream, rawValue: Long) {
        var v = rawValue
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt() and 0x7F)
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeFixed32(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, 5)
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeLengthDelimited(out: ByteArrayOutputStream, fieldNumber: Int, payload: ByteArray) {
        writeTag(out, fieldNumber, 2)
        writeVarint(out, payload.size.toLong())
        out.write(payload)
    }

    fun encodeTextToRadioProtobuf(
        text: String,
        destinationNode: Long = 0xFFFFFFFFL,
        packetId: Int = packetIdSeq.incrementAndGet(),
        hopLimit: Int = 3,
        portNum: Int = PORTNUM_TEXT_MESSAGE_APP
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8).let {
            if (it.size > 228) it.copyOf(228) else it
        }
        val dataMsg = ByteArrayOutputStream().apply {
            writeTag(this, 1, 0) // Data.portnum
            writeVarint(this, portNum.toLong())
            writeLengthDelimited(this, 2, textBytes) // Data.payload
        }.toByteArray()

        val meshPacket = ByteArrayOutputStream().apply {
            writeFixed32(this, 2, destinationNode.toInt()) // MeshPacket.to
            writeLengthDelimited(this, 4, dataMsg) // MeshPacket.decoded
            writeFixed32(this, 6, packetId) // MeshPacket.id
            writeTag(this, 9, 0) // MeshPacket.hop_limit
            writeVarint(this, hopLimit.coerceIn(1, 7).toLong())
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeLengthDelimited(this, 1, meshPacket) // ToRadio.packet
        }.toByteArray()
    }

    fun wrapSerialFrame(protobufPayload: ByteArray): ByteArray {
        val len = protobufPayload.size.coerceAtMost(MAX_PROTOBUF_BYTES)
        val out = ByteArray(4 + len)
        out[0] = SYNC_0
        out[1] = SYNC_1
        out[2] = ((len ushr 8) and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        System.arraycopy(protobufPayload, 0, out, 4, len)
        return out
    }

    fun encodeTextToRadioSerialFrame(
        text: String,
        destinationNode: Long = 0xFFFFFFFFL,
        packetId: Int = packetIdSeq.incrementAndGet(),
        hopLimit: Int = 3,
        portNum: Int = PORTNUM_TEXT_MESSAGE_APP
    ): ByteArray = wrapSerialFrame(
        encodeTextToRadioProtobuf(text, destinationNode, packetId, hopLimit, portNum)
    )

    /**
     * Builds a Meshtastic `FromRadio` packet (used by unit tests and telemetry fixtures).
     */
    fun encodeFromRadioProtobuf(
        fromNode: Long,
        text: String,
        rssiDbm: Int? = null,
        snrDb: Float? = null,
        portNum: Int = PORTNUM_TEXT_MESSAGE_APP
    ): ByteArray {
        val dataMsg = ByteArrayOutputStream().apply {
            writeTag(this, 1, 0)
            writeVarint(this, portNum.toLong())
            writeLengthDelimited(this, 2, text.toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val meshPacket = ByteArrayOutputStream().apply {
            writeFixed32(this, 1, fromNode.toInt()) // MeshPacket.from
            writeLengthDelimited(this, 4, dataMsg) // MeshPacket.decoded
            if (snrDb != null) {
                writeFixed32(this, 10, java.lang.Float.floatToIntBits(snrDb)) // MeshPacket.rx_snr
            }
            if (rssiDbm != null) {
                writeTag(this, 12, 0) // MeshPacket.rx_rssi (int32 varint)
                writeVarint(this, rssiDbm.toLong())
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeLengthDelimited(this, 2, meshPacket) // FromRadio.packet
        }.toByteArray()
    }

    private class ProtoReader(private val buf: ByteArray, private val end: Int = buf.size) {
        var pos = 0

        fun hasRemaining(): Boolean = pos < end

        fun readVarint(): Long? {
            var shift = 0
            var acc = 0L
            while (pos < end && shift <= 63) {
                val b = buf[pos++].toInt() and 0xFF
                acc = acc or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return acc
                shift += 7
            }
            return null
        }

        fun readFixed32(): Int? {
            if (pos + 4 > end) return null
            val v = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun readBytes(): ByteArray? {
            val len = readVarint()?.toInt() ?: return null
            if (len < 0 || pos + len > end) return null
            val slice = buf.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun skipField(wireType: Int): Boolean {
            return when (wireType) {
                0 -> readVarint() != null
                1 -> if (pos + 8 <= end) { pos += 8; true } else false
                2 -> readBytes() != null
                5 -> readFixed32() != null
                else -> false
            }
        }
    }

    private fun parseDataPayload(bytes: ByteArray): Pair<Int, String>? {
        val r = ProtoReader(bytes)
        var portNum = 0
        var payloadBytes: ByteArray? = null
        while (r.hasRemaining()) {
            val tag = r.readVarint()?.toInt() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                1 -> portNum = if (wire == 0) (r.readVarint()?.toInt() ?: 0) else if (r.skipField(wire)) portNum else return null
                2 -> payloadBytes = if (wire == 2) r.readBytes() else if (r.skipField(wire)) payloadBytes else return null
                else -> if (!r.skipField(wire)) return null
            }
        }
        val raw = payloadBytes ?: return null
        if (portNum != PORTNUM_TEXT_MESSAGE_APP && portNum != PORTNUM_PRIVATE_APP) return null
        return Pair(portNum, String(raw, Charsets.UTF_8))
    }

    private fun parseMeshPacket(bytes: ByteArray): LoraDecodedMessage? {
        val r = ProtoReader(bytes)
        var fromNode = 0
        var decodedText: String? = null
        var rssiDbm: Int? = null
        var snrDb: Float? = null

        while (r.hasRemaining()) {
            val tag = r.readVarint()?.toInt() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            when (field) {
                1 -> {
                    fromNode = when (wire) {
                        5 -> r.readFixed32() ?: return null
                        0 -> r.readVarint()?.toInt() ?: return null
                        else -> if (r.skipField(wire)) fromNode else return null
                    }
                }
                4 -> {
                    if (wire == 2) {
                        val sub = r.readBytes() ?: return null
                        decodedText = parseDataPayload(sub)?.second
                    } else if (!r.skipField(wire)) {
                        return null
                    }
                }
                10 -> {
                    if (wire == 5) {
                        val bits = r.readFixed32() ?: return null
                        snrDb = java.lang.Float.intBitsToFloat(bits)
                    } else if (!r.skipField(wire)) {
                        return null
                    }
                }
                12 -> {
                    if (wire == 0) {
                        rssiDbm = r.readVarint()?.toInt()
                    } else if (!r.skipField(wire)) {
                        return null
                    }
                }
                else -> if (!r.skipField(wire)) return null
            }
        }
        val txt = decodedText ?: return null
        if (txt.isEmpty()) return null
        val senderHex = "0x%08X".format(fromNode)
        return LoraDecodedMessage(
            protocol = LoraProtocol.MESHTASTIC,
            senderNodeHex = senderHex,
            text = txt,
            rssiDbm = rssiDbm,
            snrDb = snrDb
        )
    }

    fun decodeFromRadioProtobuf(payload: ByteArray): LoraDecodedMessage? {
        if (payload.isEmpty()) return null
        val r = ProtoReader(payload)
        while (r.hasRemaining()) {
            val tag = r.readVarint()?.toInt() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 2 && wire == 2) {
                val packetBytes = r.readBytes() ?: return null
                parseMeshPacket(packetBytes)?.let { return it }
            } else if (!r.skipField(wire)) {
                return null
            }
        }
        return null
    }

    fun decodeFromRadioSerialStream(buffer: ByteArray): Pair<List<LoraDecodedMessage>, ByteArray> {
        val messages = mutableListOf<LoraDecodedMessage>()
        var idx = 0
        while (idx + 3 < buffer.size) {
            if (buffer[idx] != SYNC_0 || buffer[idx + 1] != SYNC_1) {
                idx++
                continue
            }
            val len = ((buffer[idx + 2].toInt() and 0xFF) shl 8) or (buffer[idx + 3].toInt() and 0xFF)
            if (len <= 0 || len > MAX_PROTOBUF_BYTES) {
                idx++
                continue
            }
            if (idx + 4 + len > buffer.size) {
                return Pair(messages, buffer.copyOfRange(idx, buffer.size))
            }
            val frame = buffer.copyOfRange(idx + 4, idx + 4 + len)
            decodeFromRadioProtobuf(frame)?.let { messages.add(it) }
            idx += 4 + len
        }
        val rem = if (idx < buffer.size) buffer.copyOfRange(idx, buffer.size) else ByteArray(0)
        return Pair(messages, rem)
    }
}

/**
 * Pure-Kotlin MeshCore Companion Serial / BLE Nordic UART (`'<'` / `'>'`) codec.
 */
object MeshCoreWireCodec {

    private const val FRAME_OUT_START: Byte = 0x3C // '<'
    private const val FRAME_IN_START: Byte = 0x3E // '>'
    private const val MAX_FRAME_BYTES = 512

    const val CMD_SEND_CHANNEL_TXT_MSG = 0x03
    const val RESP_CONTACT_MSG_RECV = 0x07
    const val RESP_CHANNEL_MSG_RECV = 0x08

    fun encodeChannelTextSerialFrame(
        channelIdx: Int,
        text: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8).let {
            if (it.size > 160) it.copyOf(160) else it
        }
        val payloadLen = 7 + textBytes.size
        val out = ByteArray(3 + payloadLen)
        out[0] = FRAME_OUT_START
        out[1] = (payloadLen and 0xFF).toByte()
        out[2] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[3] = CMD_SEND_CHANNEL_TXT_MSG.toByte()
        out[4] = 0x00 // txt_type = plain
        out[5] = (channelIdx and 0xFF).toByte()
        out[6] = (timestampSec and 0xFF).toByte()
        out[7] = ((timestampSec ushr 8) and 0xFF).toByte()
        out[8] = ((timestampSec ushr 16) and 0xFF).toByte()
        out[9] = ((timestampSec ushr 24) and 0xFF).toByte()
        System.arraycopy(textBytes, 0, out, 10, textBytes.size)
        return out
    }

    /**
     * Builds a MeshCore `RESP_CHANNEL_MSG_RECV` serial frame (`'>'`, LE length, payload).
     */
    fun encodeChannelRecvSerialFrame(
        channelIdx: Int,
        text: String,
        pathLen: Int = 1,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payloadLen = 8 + textBytes.size
        val out = ByteArray(3 + payloadLen)
        out[0] = FRAME_IN_START
        out[1] = (payloadLen and 0xFF).toByte()
        out[2] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[3] = RESP_CHANNEL_MSG_RECV.toByte()
        out[4] = (channelIdx and 0xFF).toByte()
        out[5] = (pathLen and 0xFF).toByte()
        out[6] = 0x00 // txt_type
        out[7] = (timestampSec and 0xFF).toByte()
        out[8] = ((timestampSec ushr 8) and 0xFF).toByte()
        out[9] = ((timestampSec ushr 16) and 0xFF).toByte()
        out[10] = ((timestampSec ushr 24) and 0xFF).toByte()
        System.arraycopy(textBytes, 0, out, 11, textBytes.size)
        return out
    }

    fun decodeFramePayload(frame: ByteArray): LoraDecodedMessage? {
        if (frame.isEmpty()) return null
        val opcode = frame[0].toInt() and 0xFF
        return when (opcode) {
            RESP_CHANNEL_MSG_RECV -> {
                if (frame.size <= 8) return null
                val chanIdx = frame[1].toInt() and 0xFF
                val text = String(frame, 8, frame.size - 8, Charsets.UTF_8).trimEnd('\u0000')
                if (text.isBlank()) return null
                LoraDecodedMessage(
                    protocol = LoraProtocol.MESHCORE,
                    senderNodeHex = "MC-CH$chanIdx",
                    text = text
                )
            }
            RESP_CONTACT_MSG_RECV -> {
                if (frame.size <= 13) return null
                val senderHex = "0x" + frame.copyOfRange(1, 5).joinToString("") { "%02X".format(it) }
                val text = String(frame, 13, frame.size - 13, Charsets.UTF_8).trimEnd('\u0000')
                if (text.isBlank()) return null
                LoraDecodedMessage(
                    protocol = LoraProtocol.MESHCORE,
                    senderNodeHex = senderHex,
                    text = text
                )
            }
            else -> null
        }
    }

    fun decodeRadioSerialStream(buffer: ByteArray): Pair<List<LoraDecodedMessage>, ByteArray> {
        val messages = mutableListOf<LoraDecodedMessage>()
        var idx = 0
        while (idx + 2 < buffer.size) {
            if (buffer[idx] != FRAME_IN_START) {
                idx++
                continue
            }
            val len = (buffer[idx + 1].toInt() and 0xFF) or ((buffer[idx + 2].toInt() and 0xFF) shl 8)
            if (len <= 0 || len > MAX_FRAME_BYTES) {
                idx++
                continue
            }
            if (idx + 3 + len > buffer.size) {
                return Pair(messages, buffer.copyOfRange(idx, buffer.size))
            }
            val payload = buffer.copyOfRange(idx + 3, idx + 3 + len)
            decodeFramePayload(payload)?.let { messages.add(it) }
            idx += 3 + len
        }
        val rem = if (idx < buffer.size) buffer.copyOfRange(idx, buffer.size) else ByteArray(0)
        return Pair(messages, rem)
    }
}

/**
 * LoRa / Meshtastic / MeshCore Hardware Companion Bridge over USB-OTG Serial and BLE GATT.
 *
 * Strictly uses Android AOSP APIs (`UsbManager` + `BluetoothGatt`) with zero proprietary deps,
 * and gates all BLE calls on runtime permissions on Android 12+ (`API 31+`).
 */
class LoraCompanionBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessageReceived: (LoraDecodedMessage) -> Unit
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.kostadinosk.pulsemesh.LORA_USB_PERMISSION"

        // Silicon Labs CP210x, WCH CH340/CH341/CH9102, FTDI, Adafruit/RAK nRF52, Espressif, RP2040, Nordic
        val SUPPORTED_USB_VIDS: Set<Int> = setOf(
            0x10C4,
            0x1A86,
            0x0403,
            0x239A,
            0x303A,
            0x2E8A,
            0x1915
        )

        val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val MESHTASTIC_TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val MESHTASTIC_FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val MESHTASTIC_FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        val MESHCORE_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val MESHCORE_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val MESHCORE_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun isLikelyLoraUsbDevice(vendorId: Int, deviceClass: Int, interfaceClass: Int): Boolean {
            if (vendorId in SUPPORTED_USB_VIDS) return true
            return deviceClass == UsbConstants.USB_CLASS_COMM ||
                interfaceClass == UsbConstants.USB_CLASS_COMM ||
                interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
        }
    }

    private val _state = MutableStateFlow(LoraBridgeState())
    val state: StateFlow<LoraBridgeState> = _state.asStateFlow()

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbInEndpoint: UsbEndpoint? = null
    private var usbOutEndpoint: UsbEndpoint? = null
    private var usbReadJob: Job? = null
    private var usbReceiverRegistered = false

    private var activeGatt: BluetoothGatt? = null
    private var gattWriteChar: BluetoothGattCharacteristic? = null
    private var gattFromRadioChar: BluetoothGattCharacteristic? = null
    private var gattProtocol: LoraProtocol = LoraProtocol.MESHTASTIC

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                scanAndConnect()
            } else {
                _state.update { it.copy(statusText = "USB-OTG permission denied") }
            }
        }
    }

    fun setProtocol(protocol: LoraProtocol) {
        _state.update { it.copy(protocol = protocol) }
    }

    private fun hasBleConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun scanAndConnect() {
        _state.update { it.copy(isEnabled = true, statusText = "Scanning USB-OTG & BLE LoRa radios...") }
        val usbConnectedOrPending = tryConnectUsbOtg()
        if (usbConnectedOrPending) return
        if (!tryConnectBondedBleLora()) {
            _state.update {
                it.copy(
                    isConnected = false,
                    transport = LoraTransport.NONE,
                    statusText = "No USB-OTG or paired BLE LoRa radio detected"
                )
            }
        }
    }

    private fun tryConnectUsbOtg(): Boolean {
        val usbManager = runCatching {
            context.getSystemService(Context.USB_SERVICE) as? UsbManager
        }.getOrNull() ?: return false

        val device = runCatching {
            usbManager.deviceList.values.firstOrNull { dev ->
                var hasSerialIf = false
                for (i in 0 until dev.interfaceCount) {
                    val intf = dev.getInterface(i)
                    if (isLikelyLoraUsbDevice(dev.vendorId, dev.deviceClass, intf.interfaceClass)) {
                        hasSerialIf = true
                        break
                    }
                }
                hasSerialIf || isLikelyLoraUsbDevice(dev.vendorId, dev.deviceClass, 0)
            }
        }.getOrNull() ?: return false

        if (!usbManager.hasPermission(device)) {
            runCatching {
                if (!usbReceiverRegistered) {
                    ContextCompat.registerReceiver(
                        context,
                        usbPermissionReceiver,
                        IntentFilter(ACTION_USB_PERMISSION),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    usbReceiverRegistered = true
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context,
                    7011,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    flags
                )
                usbManager.requestPermission(device, pi)
            }
            _state.update {
                it.copy(
                    deviceName = device.productName ?: "USB LoRa (${String.format("0x%04X", device.vendorId)})",
                    statusText = "Requesting USB-OTG permission..."
                )
            }
            return true
        }

        return openUsbSerialDevice(usbManager, device)
    }

    private fun openUsbSerialDevice(usbManager: UsbManager, device: UsbDevice): Boolean {
        val conn = runCatching { usbManager.openDevice(device) }.getOrNull() ?: return false
        var chosenIntf: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
                }
            }
            if (foundIn != null && foundOut != null) {
                chosenIntf = intf
                inEp = foundIn
                outEp = foundOut
                break
            }
        }

        if (chosenIntf == null || inEp == null || outEp == null || !conn.claimInterface(chosenIntf, true)) {
            runCatching { conn.close() }
            return false
        }

        configure115200Serial(conn, chosenIntf.id)
        usbConnection = conn
        usbInterface = chosenIntf
        usbInEndpoint = inEp
        usbOutEndpoint = outEp

        val label = device.productName ?: "USB-OTG LoRa (${String.format("0x%04X", device.vendorId)})"
        val resolvedProto = when (val cur = _state.value.protocol) {
            LoraProtocol.AUTO -> if (label.contains("MeshCore", ignoreCase = true)) LoraProtocol.MESHCORE else LoraProtocol.MESHTASTIC
            else -> cur
        }
        _state.update {
            it.copy(
                isEnabled = true,
                isConnected = true,
                transport = LoraTransport.USB_OTG,
                protocol = resolvedProto,
                deviceName = label,
                statusText = "Connected via USB-OTG ($resolvedProto)"
            )
        }
        startUsbReadLoop(conn, inEp)
        return true
    }

    private fun configure115200Serial(conn: UsbDeviceConnection, intfId: Int) {
        // CDC-ACM SET_LINE_CODING (115200 8N1) + SET_CONTROL_LINE_STATE (DTR+RTS)
        val lineCoding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(115200)
            .put(0.toByte())
            .put(0.toByte())
            .put(8.toByte())
            .array()
        runCatching { conn.controlTransfer(0x21, 0x20, 0, intfId, lineCoding, lineCoding.size, 300) }
        runCatching { conn.controlTransfer(0x21, 0x22, 0x03, intfId, null, 0, 300) }
        // Silicon Labs CP210x IFC_ENABLE + SET_BAUDRATE
        runCatching { conn.controlTransfer(0x41, 0x00, 0x01, intfId, null, 0, 300) }
        val baudLe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(115200).array()
        runCatching { conn.controlTransfer(0x41, 0x1E, 0, intfId, baudLe, baudLe.size, 300) }
    }

    private fun startUsbReadLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        usbReadJob?.cancel()
        usbReadJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(maxOf(inEp.maxPacketSize, 256))
            var rxAccum = ByteArray(0)
            while (isActive && _state.value.isConnected && _state.value.transport == LoraTransport.USB_OTG) {
                val readCount = runCatching { conn.bulkTransfer(inEp, chunk, chunk.size, 250) }.getOrDefault(-1)
                if (readCount > 0) {
                    rxAccum += chunk.copyOf(readCount)
                    val activeProto = _state.value.protocol
                    val (decoded, leftover) = when (activeProto) {
                        LoraProtocol.MESHCORE -> MeshCoreWireCodec.decodeRadioSerialStream(rxAccum)
                        LoraProtocol.MESHTASTIC -> MeshtasticWireCodec.decodeFromRadioSerialStream(rxAccum)
                        LoraProtocol.AUTO -> {
                            val mRes = MeshtasticWireCodec.decodeFromRadioSerialStream(rxAccum)
                            if (mRes.first.isNotEmpty()) mRes
                            else MeshCoreWireCodec.decodeRadioSerialStream(rxAccum)
                        }
                    }
                    rxAccum = if (leftover.size > 1024) ByteArray(0) else leftover
                    for (msg in decoded) {
                        handleDecodedLoraMessage(msg)
                    }
                } else {
                    delay(25L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectBondedBleLora(): Boolean {
        if (!hasBleConnectPermission()) return false
        val btManager = runCatching {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }.getOrNull() ?: return false
        val adapter: BluetoothAdapter = btManager.adapter ?: return false
        val candidate: BluetoothDevice = runCatching {
            adapter.bondedDevices?.firstOrNull { dev ->
                val name = dev.name.orEmpty().lowercase()
                name.contains("meshtastic") ||
                    name.contains("meshcore") ||
                    name.contains("t-beam") ||
                    name.contains("heltec") ||
                    name.contains("rak") ||
                    name.contains("lilygo") ||
                    name.contains("lora")
            }
        }.getOrNull() ?: return false

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.update {
                        it.copy(
                            isConnected = true,
                            transport = LoraTransport.BLE_GATT,
                            deviceName = candidate.name ?: candidate.address,
                            statusText = "Discovering LoRa GATT services..."
                        )
                    }
                    if (hasBleConnectPermission()) {
                        runCatching { gatt.discoverServices() }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.update {
                        it.copy(
                            isConnected = false,
                            transport = LoraTransport.NONE,
                            statusText = "BLE LoRa radio disconnected"
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || !hasBleConnectPermission()) return
                val meshtasticSvc = gatt.getService(MESHTASTIC_SERVICE_UUID)
                val meshcoreSvc = gatt.getService(MESHCORE_SERVICE_UUID)
                when {
                    meshtasticSvc != null -> {
                        gattProtocol = LoraProtocol.MESHTASTIC
                        gattWriteChar = meshtasticSvc.getCharacteristic(MESHTASTIC_TORADIO_UUID)
                        gattFromRadioChar = meshtasticSvc.getCharacteristic(MESHTASTIC_FROMRADIO_UUID)
                        meshtasticSvc.getCharacteristic(MESHTASTIC_FROMNUM_UUID)?.let { enableGattNotify(gatt, it) }
                        _state.update {
                            it.copy(
                                protocol = LoraProtocol.MESHTASTIC,
                                statusText = "Meshtastic BLE GATT Ready (${candidate.name ?: "LoRa"})"
                            )
                        }
                    }
                    meshcoreSvc != null -> {
                        gattProtocol = LoraProtocol.MESHCORE
                        gattWriteChar = meshcoreSvc.getCharacteristic(MESHCORE_RX_UUID)
                        meshcoreSvc.getCharacteristic(MESHCORE_TX_UUID)?.let { enableGattNotify(gatt, it) }
                        _state.update {
                            it.copy(
                                protocol = LoraProtocol.MESHCORE,
                                statusText = "MeshCore BLE NUS Ready (${candidate.name ?: "LoRa"})"
                            )
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                handleGattBytes(characteristic.uuid, characteristic.value ?: ByteArray(0), gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleGattBytes(characteristic.uuid, value, gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == MESHTASTIC_FROMRADIO_UUID) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value ?: ByteArray(0)
                    if (data.isNotEmpty()) {
                        MeshtasticWireCodec.decodeFromRadioProtobuf(data)?.let(::handleDecodedLoraMessage)
                        if (hasBleConnectPermission()) runCatching { gatt.readCharacteristic(characteristic) }
                    }
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == MESHTASTIC_FROMRADIO_UUID && value.isNotEmpty()) {
                    MeshtasticWireCodec.decodeFromRadioProtobuf(value)?.let(::handleDecodedLoraMessage)
                    if (hasBleConnectPermission()) runCatching { gatt.readCharacteristic(characteristic) }
                }
            }
        }

        activeGatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                candidate.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                candidate.connectGatt(context, false, gattCallback)
            }
        }.getOrNull()
        return activeGatt != null
    }

    @SuppressLint("MissingPermission")
    private fun enableGattNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        if (!hasBleConnectPermission()) return
        runCatching {
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGattBytes(uuid: UUID, bytes: ByteArray, gatt: BluetoothGatt) {
        when (uuid) {
            MESHTASTIC_FROMNUM_UUID -> {
                val fromRadio = gattFromRadioChar ?: return
                if (hasBleConnectPermission()) runCatching { gatt.readCharacteristic(fromRadio) }
            }
            MESHTASTIC_FROMRADIO_UUID -> {
                MeshtasticWireCodec.decodeFromRadioProtobuf(bytes)?.let(::handleDecodedLoraMessage)
            }
            MESHCORE_TX_UUID -> {
                MeshCoreWireCodec.decodeFramePayload(bytes)?.let(::handleDecodedLoraMessage)
            }
        }
    }

    private fun handleDecodedLoraMessage(msg: LoraDecodedMessage) {
        _state.update {
            it.copy(
                rxFrames = it.rxFrames + 1,
                lastRssiDbm = msg.rssiDbm ?: it.lastRssiDbm,
                lastSnrDb = msg.snrDb ?: it.lastSnrDb,
                statusText = "RX ${msg.protocol.name} from ${msg.senderNodeHex}"
            )
        }
        onMessageReceived(msg)
    }

    @SuppressLint("MissingPermission")
    fun sendTextFrame(text: String): Boolean {
        val st = _state.value
        if (!st.isConnected || text.isBlank()) return false

        val ok = when (st.transport) {
            LoraTransport.USB_OTG -> {
                val conn = usbConnection ?: return false
                val ep = usbOutEndpoint ?: return false
                val frame = when (st.protocol) {
                    LoraProtocol.MESHCORE -> MeshCoreWireCodec.encodeChannelTextSerialFrame(0, text)
                    else -> MeshtasticWireCodec.encodeTextToRadioSerialFrame(text)
                }
                val sent = runCatching { conn.bulkTransfer(ep, frame, frame.size, 800) }.getOrDefault(-1)
                sent == frame.size
            }
            LoraTransport.BLE_GATT -> {
                val gatt = activeGatt ?: return false
                val ch = gattWriteChar ?: return false
                if (!hasBleConnectPermission()) return false
                val payload = when (gattProtocol) {
                    LoraProtocol.MESHCORE -> MeshCoreWireCodec.encodeChannelTextSerialFrame(0, text).drop(3).toByteArray()
                    else -> MeshtasticWireCodec.encodeTextToRadioProtobuf(text)
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ch.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(ch)
                    }
                }.getOrDefault(false)
            }
            LoraTransport.NONE -> false
        }

        if (ok) {
            _state.update { it.copy(txFrames = it.txFrames + 1) }
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        usbReadJob?.cancel()
        usbReadJob = null
        runCatching { usbInterface?.let { usbConnection?.releaseInterface(it) } }
        runCatching { usbConnection?.close() }
        usbInterface = null
        usbConnection = null
        usbInEndpoint = null
        usbOutEndpoint = null

        if (hasBleConnectPermission()) {
            runCatching { activeGatt?.disconnect() }
            runCatching { activeGatt?.close() }
        }
        activeGatt = null
        gattWriteChar = null
        gattFromRadioChar = null

        if (usbReceiverRegistered) {
            runCatching { context.unregisterReceiver(usbPermissionReceiver) }
            usbReceiverRegistered = false
        }

        _state.update {
            it.copy(
                isEnabled = false,
                isConnected = false,
                transport = LoraTransport.NONE,
                deviceName = "",
                statusText = "Idle (USB-OTG / BLE)"
            )
        }
    }
}
