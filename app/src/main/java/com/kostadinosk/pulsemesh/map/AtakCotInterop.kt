// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import android.os.Build
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.model.TacticalWaypoint
import com.kostadinosk.pulsemesh.model.WaypointType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CotEvent(
    val uid: String,
    val type: String,
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val hae: Double = 0.0,
    val remarks: String = "",
    val timeIso: String = "",
    val staleIso: String = ""
)

/**
 * ATAK Cursor-on-Target (CoT) XML codec + optional local LAN/Wi-Fi UDP multicast bridge (`239.2.3.1:6969`).
 */
object AtakCotInterop {

    const val COT_MULTICAST_GROUP = "239.2.3.1"
    const val COT_MULTICAST_PORT = 6969

    private fun xmlEscape(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(ms))
    }

    fun encodeNodeToCotXml(node: MeshNode, staleSeconds: Int = 300): String {
        val now = System.currentTimeMillis()
        val timeIso = isoUtc(now)
        val staleIso = isoUtc(now + staleSeconds * 1000L)
        val uid = "PulseMesh-${xmlEscape(node.id)}"
        val callsign = xmlEscape(node.callsign.ifBlank { node.id })
        val remarks = xmlEscape("PulseMesh Node ${node.id} • Bat ${node.batteryPercent}% • ${node.lastBlePhy}")
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="$uid" type="a-f-G-U-C" how="m-g" time="$timeIso" start="$timeIso" stale="$staleIso">
  <point lat="${node.latitude}" lon="${node.longitude}" hae="${node.altitudeM}" ce="15.0" le="25.0"/>
  <detail>
    <contact callsign="$callsign"/>
    <__group name="Cyan" role="Team Member"/>
    <remarks>$remarks</remarks>
  </detail>
</event>"""
    }

    fun encodeWaypointToCotXml(wp: TacticalWaypoint, staleSeconds: Int = 900): String {
        val now = System.currentTimeMillis()
        val timeIso = isoUtc(now)
        val staleIso = isoUtc(now + staleSeconds * 1000L)
        val cotType = when (wp.type) {
            WaypointType.HAZARD -> "a-h-G"
            WaypointType.SOS -> "b-a-o-tbl"
            else -> "b-m-p-w"
        }
        val uid = "PulseMesh-WP-${xmlEscape(wp.id)}"
        val title = xmlEscape(wp.title)
        val remarks = xmlEscape("${wp.type.name} dropped by ${wp.creatorCallsign}")
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="$uid" type="$cotType" how="h-g-i-g-o" time="$timeIso" start="$timeIso" stale="$staleIso">
  <point lat="${wp.latitude}" lon="${wp.longitude}" hae="0.0" ce="10.0" le="25.0"/>
  <detail>
    <contact callsign="$title"/>
    <remarks>$remarks</remarks>
  </detail>
</event>"""
    }

    fun parseCotXml(xml: String): CotEvent? {
        if (xml.isBlank()) return null
        return runCatching {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            parser.setInput(StringReader(xml))

            var uid = ""
            var type = ""
            var timeIso = ""
            var staleIso = ""
            var lat: Double? = null
            var lon: Double? = null
            var hae = 0.0
            var callsign = ""
            var remarks = ""
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.orEmpty().lowercase(Locale.US)
                        when (currentTag) {
                            "event" -> {
                                uid = parser.getAttributeValue(null, "uid").orEmpty()
                                type = parser.getAttributeValue(null, "type").orEmpty()
                                timeIso = parser.getAttributeValue(null, "time").orEmpty()
                                staleIso = parser.getAttributeValue(null, "stale").orEmpty()
                            }
                            "point" -> {
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                            }
                            "contact" -> {
                                callsign = parser.getAttributeValue(null, "callsign").orEmpty()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag == "remarks") {
                            val t = parser.text?.trim().orEmpty()
                            if (t.isNotEmpty()) remarks = t
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                eventType = parser.next()
            }

            if (uid.isBlank() || type.isBlank() || lat == null || lon == null) null
            else CotEvent(
                uid = uid,
                type = type,
                callsign = callsign.ifBlank { uid },
                lat = lat,
                lon = lon,
                hae = hae,
                remarks = remarks,
                timeIso = timeIso,
                staleIso = staleIso
            )
        }.getOrNull()
    }
}

class AtakCotUdpBridge(private val scope: CoroutineScope) {

    @Volatile
    private var listenJob: Job? = null

    @Volatile
    private var rxSocket: MulticastSocket? = null

    private fun isRobolectric(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
    }

    fun sendCotXml(xml: String) {
        if (isRobolectric() || xml.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = xml.toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(AtakCotInterop.COT_MULTICAST_GROUP)
                MulticastSocket().use { sock ->
                    sock.timeToLive = 2
                    sock.send(DatagramPacket(bytes, bytes.size, group, AtakCotInterop.COT_MULTICAST_PORT))
                }
            }
        }
    }

    fun startListening(onCotReceived: (CotEvent) -> Unit) {
        if (isRobolectric()) return
        stop()
        listenJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val group = InetAddress.getByName(AtakCotInterop.COT_MULTICAST_GROUP)
                val sock = MulticastSocket(AtakCotInterop.COT_MULTICAST_PORT).apply {
                    reuseAddress = true
                    joinGroup(group)
                    soTimeout = 3_000
                }
                rxSocket = sock
                val buf = ByteArray(16_384)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok = runCatching {
                        sock.receive(pkt)
                        true
                    }.getOrDefault(false)
                    if (!ok) continue
                    val rawXml = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    AtakCotInterop.parseCotXml(rawXml)?.let { ev ->
                        onCotReceived(ev)
                    }
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        runCatching { rxSocket?.close() }
        rxSocket = null
    }
}
