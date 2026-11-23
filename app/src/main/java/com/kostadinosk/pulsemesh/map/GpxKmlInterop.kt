// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import com.kostadinosk.pulsemesh.model.BreadcrumbPoint
import com.kostadinosk.pulsemesh.model.TacticalWaypoint
import com.kostadinosk.pulsemesh.model.WaypointType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ImportedMapData(
    val waypoints: List<TacticalWaypoint>,
    val trackPoints: List<BreadcrumbPoint>
)

/**
 * Pure-Kotlin GPX 1.1 & KML 2.2 tactical waypoint + breadcrumb track exporter/importer.
 */
object GpxKmlInterop {

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
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(ms))
    }

    fun exportGpx(
        waypoints: List<TacticalWaypoint>,
        tracks: Map<String, List<BreadcrumbPoint>>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"PulseMesh 1.5.5\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (wp in waypoints) {
            sb.append("  <wpt lat=\"${wp.latitude}\" lon=\"${wp.longitude}\">\n")
            sb.append("    <time>${isoUtc(wp.timestamp)}</time>\n")
            sb.append("    <name>${xmlEscape(wp.title)}</name>\n")
            sb.append("    <type>${xmlEscape(wp.type.name)}</type>\n")
            sb.append("    <desc>${xmlEscape(wp.creatorCallsign)}</desc>\n")
            sb.append("  </wpt>\n")
        }
        for ((trackName, pts) in tracks) {
            if (pts.isEmpty()) continue
            sb.append("  <trk>\n")
            sb.append("    <name>${xmlEscape(trackName)}</name>\n")
            sb.append("    <trkseg>\n")
            for (pt in pts) {
                sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lon}\">\n")
                sb.append("        <ele>${pt.altM}</ele>\n")
                sb.append("        <time>${isoUtc(pt.timestamp)}</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun importGpx(xml: String): ImportedMapData {
        val waypoints = mutableListOf<TacticalWaypoint>()
        val trackPoints = mutableListOf<BreadcrumbPoint>()
        if (xml.isBlank()) return ImportedMapData(emptyList(), emptyList())

        runCatching {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            parser.setInput(StringReader(xml))

            var inWpt = false
            var inTrkpt = false
            var lat = 0.0
            var lon = 0.0
            var ele = 0.0
            var name = ""
            var typeStr = "RALLY"
            var desc = "GPX Import"
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.orEmpty().lowercase(Locale.US)
                        when (currentTag) {
                            "wpt" -> {
                                inWpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                name = ""
                                typeStr = "RALLY"
                                desc = "GPX Import"
                            }
                            "trkpt" -> {
                                inTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                ele = 0.0
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            if (inWpt) {
                                when (currentTag) {
                                    "name" -> name = txt
                                    "type" -> typeStr = txt
                                    "desc" -> desc = txt
                                }
                            } else if (inTrkpt && currentTag == "ele") {
                                ele = txt.toDoubleOrNull() ?: 0.0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endName = parser.name.orEmpty().lowercase(Locale.US)
                        if (endName == "wpt" && inWpt) {
                            val wpType = runCatching { WaypointType.valueOf(typeStr.uppercase(Locale.US)) }
                                .getOrDefault(WaypointType.RALLY)
                            waypoints.add(
                                TacticalWaypoint(
                                    id = "gpx_${UUID.randomUUID().toString().take(8)}",
                                    title = name.ifBlank { wpType.label },
                                    type = wpType,
                                    latitude = lat,
                                    longitude = lon,
                                    creatorCallsign = desc,
                                    timestamp = System.currentTimeMillis(),
                                    payloadHex = "GPX"
                                )
                            )
                            inWpt = false
                        } else if (endName == "trkpt" && inTrkpt) {
                            trackPoints.add(BreadcrumbPoint(lat = lat, lon = lon, altM = ele))
                            inTrkpt = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        }
        return ImportedMapData(waypoints, trackPoints)
    }

    fun exportKml(
        waypoints: List<TacticalWaypoint>,
        tracks: Map<String, List<BreadcrumbPoint>>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>PulseMesh Tactical Overlay</name>\n")
        for (wp in waypoints) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>${xmlEscape(wp.title)}</name>\n")
            sb.append("      <description>${xmlEscape("${wp.type.name}|${wp.creatorCallsign}")}</description>\n")
            sb.append("      <Point><coordinates>${wp.longitude},${wp.latitude},0</coordinates></Point>\n")
            sb.append("    </Placemark>\n")
        }
        for ((trackName, pts) in tracks) {
            if (pts.size < 2) continue
            sb.append("    <Placemark>\n")
            sb.append("      <name>${xmlEscape(trackName)}</name>\n")
            sb.append("      <LineString><coordinates>")
            sb.append(pts.joinToString(" ") { "${it.lon},${it.lat},${it.altM}" })
            sb.append("</coordinates></LineString>\n")
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
        return sb.toString()
    }

    fun importKml(xml: String): ImportedMapData {
        val waypoints = mutableListOf<TacticalWaypoint>()
        val trackPoints = mutableListOf<BreadcrumbPoint>()
        if (xml.isBlank()) return ImportedMapData(emptyList(), emptyList())

        runCatching {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            parser.setInput(StringReader(xml))

            var inPlacemark = false
            var inPoint = false
            var inLineString = false
            var currentTag = ""
            var name = ""
            var desc = ""
            var coordsText = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.orEmpty().lowercase(Locale.US)
                        when (currentTag) {
                            "placemark" -> {
                                inPlacemark = true
                                inPoint = false
                                inLineString = false
                                name = ""
                                desc = ""
                                coordsText = ""
                            }
                            "point" -> if (inPlacemark) inPoint = true
                            "linestring" -> if (inPlacemark) inLineString = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty() && inPlacemark) {
                            when (currentTag) {
                                "name" -> name = txt
                                "description" -> desc = txt
                                "coordinates" -> coordsText = txt
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endTag = parser.name.orEmpty().lowercase(Locale.US)
                        if (endTag == "placemark" && inPlacemark) {
                            val tuples = coordsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (inPoint && tuples.isNotEmpty()) {
                                val parts = tuples.first().split(',')
                                val lon = parts.getOrNull(0)?.toDoubleOrNull()
                                val lat = parts.getOrNull(1)?.toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    val typeToken = desc.substringBefore('|').trim()
                                    val creator = desc.substringAfter('|', "KML Import").ifBlank { "KML Import" }
                                    val wpType = runCatching { WaypointType.valueOf(typeToken.uppercase(Locale.US)) }
                                        .getOrDefault(WaypointType.RALLY)
                                    waypoints.add(
                                        TacticalWaypoint(
                                            id = "kml_${UUID.randomUUID().toString().take(8)}",
                                            title = name.ifBlank { wpType.label },
                                            type = wpType,
                                            latitude = lat,
                                            longitude = lon,
                                            creatorCallsign = creator,
                                            timestamp = System.currentTimeMillis(),
                                            payloadHex = "KML"
                                        )
                                    )
                                }
                            } else if (inLineString) {
                                for (tuple in tuples) {
                                    val parts = tuple.split(',')
                                    val lon = parts.getOrNull(0)?.toDoubleOrNull()
                                    val lat = parts.getOrNull(1)?.toDoubleOrNull()
                                    val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                                    if (lat != null && lon != null) {
                                        trackPoints.add(BreadcrumbPoint(lat = lat, lon = lon, altM = alt))
                                    }
                                }
                            }
                            inPlacemark = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        }
        return ImportedMapData(waypoints, trackPoints)
    }

    fun parseGpxOrKml(xml: String): ImportedMapData {
        val trimmed = xml.trim()
        if (trimmed.isEmpty()) return ImportedMapData(emptyList(), emptyList())
        return if (trimmed.contains("<kml", ignoreCase = true) || trimmed.contains("<Placemark", ignoreCase = true)) {
            importKml(trimmed)
        } else {
            importGpx(trimmed)
        }
    }
}
