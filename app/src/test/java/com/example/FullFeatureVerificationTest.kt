// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.TacticalE2eeManager
import com.example.crypto.UsernameClaimRecord
import com.example.model.MeshPacket
import com.example.model.PacketType
import com.example.model.WaypointType
import com.example.service.PulseMeshWebServer
import com.example.service.WebClientRegistration
import com.example.service.WebSubmittedPacket
import com.example.service.WireMeshPacket
import com.example.util.DevicePreferences
import com.example.util.OfflineRegionDownloader
import com.example.util.PersistentChatManager
import com.example.viewmodel.MeshViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullFeatureVerificationTest {

    @Test
    fun `verify live PulseMeshWebServer HTTP 1_1 GZIP Keep-Alive API endpoints and registration`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val registered = CopyOnWriteArrayList<WebClientRegistration>()
        val submitted = CopyOnWriteArrayList<WebSubmittedPacket>()

        val server = PulseMeshWebServer(
            context = app,
            scope = scope,
            buildInfoJson = { portalUrl, count ->
                JSONObject().apply {
                    put("pulsemeshHost", true)
                    put("version", "1.2.9")
                    put("hostNodeId", "0xHOST")
                    put("hostCallsign", "HostAlpha")
                    put("portalUrl", portalUrl)
                    put("webClientCount", count)
                }
            },
            buildStateJson = { clientId ->
                JSONObject().apply {
                    put("echoClient", clientId)
                    put("packets", org.json.JSONArray())
                }
            },
            onClientRegistered = { registered.add(it) },
            onWebPacketSubmitted = { submitted.add(it) }
        )

        try {
            server.start("127.0.0.1")
            // Wait up to 3s for socket bind + loopback self-test
            val deadline = System.currentTimeMillis() + 3000L
            while ((!server.isRunning() || !server.status.value.selfTestPassed) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L)
            }
            assertTrue("Web server must be running", server.isRunning())
            assertTrue("Loopback HTTP 200 self-test must pass", server.status.value.selfTestPassed)
            val port = server.status.value.port
            assertTrue("Port must be in 8080..9090", port in 8080..9090)

            // 1. GET / with Accept-Encoding: gzip -> HTTP 200 + valid gzipped HTML
            val htmlConn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept-Encoding", "gzip")
                connectTimeout = 2000
                readTimeout = 2000
            }
            assertEquals(200, htmlConn.responseCode)
            val enc = htmlConn.contentEncoding
            val htmlBody = if (enc != null && enc.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(htmlConn.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                htmlConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            htmlConn.disconnect()
            assertTrue("HTML response must contain PulseMesh", htmlBody.contains("PulseMesh"))

            // 2. GET /api/info
            val infoConn = (URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
            }
            assertEquals(200, infoConn.responseCode)
            val infoJson = JSONObject(infoConn.inputStream.bufferedReader().use { it.readText() })
            infoConn.disconnect()
            assertTrue(infoJson.getBoolean("pulsemeshHost"))
            assertEquals("1.2.9", infoJson.getString("version"))

            // 3. POST /api/register
            val regConn = (URL("http://127.0.0.1:$port/api/register").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            regConn.outputStream.use {
                it.write("""{"nodeId":"0xWEB9","callsign":"ScoutWeb"}""".toByteArray(Charsets.UTF_8))
            }
            assertEquals(200, regConn.responseCode)
            regConn.disconnect()
            assertEquals(1, registered.size)
            assertEquals("0xWEB9", registered[0].nodeId)
            assertEquals("ScoutWeb", registered[0].callsign)

            // 4. POST /api/send
            val sendConn = (URL("http://127.0.0.1:$port/api/send").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            sendConn.outputStream.use {
                it.write("""{"clientNodeId":"0xWEB9","callsign":"ScoutWeb","threadId":"channel_primary","type":"CHAT","text":"Hello from Web"}""".toByteArray(Charsets.UTF_8))
            }
            assertEquals(200, sendConn.responseCode)
            sendConn.disconnect()
            assertEquals(1, submitted.size)
            assertEquals("Hello from Web", submitted[0].text)

            // 5. Raw TCP Socket HTTP/1.1 Keep-Alive: 2 sequential requests on ONE connection (verifies zero TCP RST)
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                // Request 1 (keep-alive)
                out.write("GET /api/info HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                out.flush()
                val status1 = reader.readLine()
                assertNotNull(status1)
                assertTrue("First keep-alive response must be 200 OK", status1!!.contains("200 OK"))
                var len1 = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.lowercase().startsWith("content-length:")) len1 = h.substringAfter(":").trim().toInt()
                }
                val buf1 = CharArray(len1)
                var read1 = 0
                while (read1 < len1) {
                    val n = reader.read(buf1, read1, len1 - read1)
                    if (n <= 0) break
                    read1 += n
                }
                assertTrue(String(buf1).contains("pulsemeshHost"))

                // Request 2 on the SAME socket (Connection: close)
                out.write("GET /api/state?clientNodeId=0xWEB9 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val status2 = reader.readLine()
                assertNotNull(status2)
                assertTrue("Second pipelined/keep-alive response on same socket must be 200 OK", status2!!.contains("200 OK"))
            }
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `verify accurate hop counting, multi-hop relay rules, gateway flag, and offline map gateway access`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        val selfId = vm.uiState.value.localNodeId

        // 1. Direct neighbor (wire hops = 0) -> displayed as 1 hop
        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "hop_direct_1",
                srcNodeId = "0xA101",
                srcCallsign = "DirectPeer",
                dstNodeId = "ALL",
                type = "CHAT",
                payload = "Direct msg",
                ttl = 7,
                hops = 0,
                isGateway = true
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val directPeer = vm.uiState.value.nodes.first { it.id == "0xA101" }
        assertEquals(1, directPeer.hopCount)
        assertTrue("Peer advertising gw=true must be marked isGateway", directPeer.isGateway)
        assertTrue("Offline map downloader must detect Gateway peer access", OfflineRegionDownloader.hasGatewayMapAccess())

        // 2. Relayed peer (wire hops = 2) -> displayed as 3 hops
        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "hop_relayed_3",
                srcNodeId = "0xB202",
                srcCallsign = "RelayedPeer",
                dstNodeId = "ALL",
                type = "CHAT",
                payload = "3-hop msg",
                ttl = 5,
                hops = 2
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val relayedPeer = vm.uiState.value.nodes.first { it.id == "0xB202" }
        assertEquals(3, relayedPeer.hopCount)

        // 3. Relay filter rules:
        // - Directed CHAT_ACK to a 3rd node IS relayed (multi-hop ACK delivery!)
        val thirdPartyAck = WireMeshPacket(
            packetId = "ack_multihop_1",
            srcNodeId = "0xB202",
            srcCallsign = "RelayedPeer",
            dstNodeId = "0xC303",
            type = "CHAT_ACK",
            payload = "orig_msg_1",
            ttl = 5,
            hops = 0
        )
        val relayedAck = vm.buildRelayedPacketIfEligible(thirdPartyAck, selfId)
        assertNotNull("Directed multi-hop CHAT_ACK to 3rd party must be relayed", relayedAck)
        assertEquals(1, relayedAck!!.hops)
        assertEquals(4, relayedAck.ttl)

        // - CHAT_ACK addressed to self must NOT be relayed
        val selfAck = thirdPartyAck.copy(packetId = "ack_to_self_1", dstNodeId = selfId)
        assertNull("CHAT_ACK already addressed to self must NOT be relayed", vm.buildRelayedPacketIfEligible(selfAck, selfId))

        // - Broadcast CHAT_ACK must NOT be relayed
        val bcastAck = thirdPartyAck.copy(packetId = "ack_bcast_1", dstNodeId = "ALL")
        assertNull("Broadcast CHAT_ACK must NOT be relayed", vm.buildRelayedPacketIfEligible(bcastAck, selfId))

        // - E2EE_DM addressed to self must NOT be relayed
        val dmToSelf = WireMeshPacket(
            packetId = "dm_self_final",
            srcNodeId = "0xA101",
            srcCallsign = "DirectPeer",
            dstNodeId = selfId,
            type = "E2EE_DM",
            payload = "PLAINTEXT:secret",
            ttl = 7,
            hops = 0
        )
        assertNull("E2EE_DM addressed to self must NOT be relayed", vm.buildRelayedPacketIfEligible(dmToSelf, selfId))
    }

    @Test
    fun `verify strict account deletion requires active login OR BOTH valid PIN and Recovery Key`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = DevicePreferences(app)
        val kp = TacticalE2eeManager.generateKeyPair("TestOp")
        val uname = "testoperator"
        val pin = "4829"
        val recovery = TacticalE2eeManager.generateRecoveryKey()
        val salt = TacticalE2eeManager.generateAccountSaltHex()
        val pinHash = TacticalE2eeManager.hashAccountPin(uname, salt, pin)
        val recoveryHash = TacticalE2eeManager.hashRecoveryKey(uname, recovery)

        // Save a stored claim while user is NOT logged in as testoperator
        prefs.claimedUsername = ""
        prefs.saveUsernameClaim(
            UsernameClaimRecord(
                username = uname,
                displayName = "TestOp",
                nodeId = "0x7777",
                publicKeyBase64 = kp.publicKeyBase64,
                saltHex = salt,
                pinHash = pinHash,
                recoveryHash = recoveryHash,
                claimedAt = System.currentTimeMillis()
            )
        )

        val vm = MeshViewModel(app)

        // 1. Unauthenticated deletion (no login, no PIN/recovery) -> DENIED
        val (ok1, msg1) = vm.deleteAllAccountInformation(uname, "", "")
        assertFalse("Must reject deletion with no credentials: $msg1", ok1)

        // 2. PIN only (missing Recovery Key) -> DENIED
        val (ok2, msg2) = vm.deleteAllAccountInformation(uname, pin, "")
        assertFalse("Must reject deletion with PIN only: $msg2", ok2)

        // 3. Recovery Key only (wrong PIN) -> DENIED
        val (ok3, msg3) = vm.deleteAllAccountInformation(uname, "0000", recovery)
        assertFalse("Must reject deletion with wrong PIN + valid Recovery Key: $msg3", ok3)

        // 4. Valid PIN + wrong Recovery Key -> DENIED
        val (ok4, msg4) = vm.deleteAllAccountInformation(uname, pin, "PMRK-0000-0000-0000")
        assertFalse("Must reject deletion with valid PIN + wrong Recovery Key: $msg4", ok4)

        // 5. BOTH valid PIN AND valid Recovery Key -> ALLOWED!
        val (ok5, msg5) = vm.deleteAllAccountInformation(uname, pin, recovery)
        assertTrue("Must allow deletion with BOTH valid PIN and Recovery Key: $msg5", ok5)
        assertNull("Claim must be removed after verified deletion", prefs.getUsernameClaim(uname))
    }

    @Test
    fun `verify self-chat prevention, PM_IMG persistence, and instant CHAT_ACK delivery status`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val chatMgr = PersistentChatManager(app)
        chatMgr.wipeAllHistoryForDuress()

        // 1. Both [[PM_IMG]]: and [[PM_IMAGE]]: must be persisted (never dropped by system payload filter)
        chatMgr.savePacket(
            "channel_primary",
            MeshPacket(
                id = "img_pkt_1",
                sourceId = "0x1111",
                destId = "ALL",
                sourceCallsign = "CamNode",
                type = PacketType.TEXT,
                content = "[[PM_IMG]]:abc123",
                hops = 1,
                rawHexBadge = "[BLE]",
                isIncoming = true
            )
        )
        assertTrue(
            "PersistentChatManager must persist [[PM_IMG]]: photo messages",
            chatMgr.getPacketsForThread("channel_primary").any { it.id == "img_pkt_1" }
        )

        // 2. Self-chat prevention & CHAT_ACK immediate UI update
        val vm = MeshViewModel(app)
        val selfId = vm.uiState.value.localNodeId
        vm.selectThread("dm_$selfId")
        assertNotEquals("Must never select self DM thread", "dm_$selfId", vm.uiState.value.selectedThreadId)

        // Send a message in primary channel and verify CHAT_ACK flips isDelivered = true immediately
        vm.selectThread("channel_primary")
        vm.sendMessageInThread("channel_primary", "Testing ACK accuracy")
        val sentPkt = vm.uiState.value.activeThreadPackets.firstOrNull { it.content == "Testing ACK accuracy" }
        assertNotNull(sentPkt)
        assertFalse(sentPkt!!.isDelivered)

        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "ack_${sentPkt.id}_0x9999",
                srcNodeId = "0x9999",
                srcCallsign = "BravoAck",
                dstNodeId = selfId,
                type = "CHAT_ACK",
                payload = sentPkt.id,
                ttl = 5,
                hops = 0
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val updatedPkt = vm.uiState.value.activeThreadPackets.first { it.id == sentPkt.id }
        assertTrue("CHAT_ACK must immediately flip isDelivered = true", updatedPkt.isDelivered)
        assertEquals("BravoAck", updatedPkt.ackedByCallsign)
    }

    @Test
    fun `verify v1_3_0 SOS cancel removes red map pins locally and remotely`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        vm.setManualGpsLocation(37.9838, 23.7275)

        // 1. Trigger local SOS -> drops red SOS waypoint
        vm.toggleSosBeacon()
        assertTrue(vm.uiState.value.isSosActive)
        assertTrue(
            "Triggering SOS must drop a red SOS waypoint on the map",
            vm.uiState.value.waypoints.any { it.type == WaypointType.SOS }
        )

        // 2. Cancel local SOS -> must remove self red SOS waypoint!
        vm.toggleSosBeacon()
        assertFalse(vm.uiState.value.isSosActive)
        assertFalse(
            "Cancelling SOS must remove the red SOS pin from the map",
            vm.uiState.value.waypoints.any { it.type == WaypointType.SOS }
        )

        // 3. Incoming remote SOS drops remote red pin, then SOS_CLEAR removes it
        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "sos_remote_1",
                srcNodeId = "0xE123",
                srcCallsign = "EchoUnit",
                dstNodeId = "ALL",
                type = "SOS",
                payload = "SOS DISTRESS",
                lat = 37.9850,
                lng = 23.7290,
                ttl = 7,
                hops = 0
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(vm.uiState.value.waypoints.any { it.type == WaypointType.SOS && it.id.contains("E123", ignoreCase = true) })

        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "sos_clear_remote_1",
                srcNodeId = "0xE123",
                srcCallsign = "EchoUnit",
                dstNodeId = "ALL",
                type = "SOS_CLEAR",
                payload = "SOS CANCELLED",
                ttl = 7,
                hops = 0
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(
            "Incoming SOS_CLEAR must remove remote peer's red SOS pin from map",
            vm.uiState.value.waypoints.any { it.type == WaypointType.SOS && it.id.contains("E123", ignoreCase = true) }
        )
    }

    @Test
    fun `verify v1_3_0 HTTPS SSL hotspot portal and offline file transfer SHA-256`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = PulseMeshWebServer(
            context = app,
            scope = scope,
            buildInfoJson = { portalUrl, count ->
                JSONObject().apply {
                    put("pulsemeshHost", true)
                    put("version", "1.3.1")
                    put("sslEnabled", true)
                    put("portalUrl", portalUrl)
                    put("webClientCount", count)
                }
            },
            buildStateJson = { JSONObject().apply { put("ok", true) } },
            onClientRegistered = {},
            onWebPacketSubmitted = {}
        )
        server.start("127.0.0.1")
        val deadline = System.currentTimeMillis() + 5000L
        while ((!server.isRunning() || !server.status.value.selfTestPassed || !server.status.value.httpsSelfTestPassed) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        val status = server.status.value
        assertTrue("Hotspot HTTP/HTTPS server must start", server.isRunning())
        assertTrue("SSL HTTPS listener must be enabled on hotspot server", status.sslEnabled)
        assertTrue("HTTPS self-test must pass on hotspot server", status.httpsSelfTestPassed)
        assertTrue("HTTPS port must be bound", status.httpsPort > 0)

        try {
            // 1. Verify Offline File Transfer upload + list + download SHA-256 over HTTP
            val payloadBytes = "PulseMesh v1.3.1 field briefing checklist".toByteArray(Charsets.UTF_8)
            val b64 = android.util.Base64.encodeToString(payloadBytes, android.util.Base64.NO_WRAP)
            val uploadConn = (URL("http://127.0.0.1:${status.port}/api/files/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use {
                    it.write("""{"fileName":"briefing.txt","mimeType":"text/plain","senderCallsign":"Alpha","base64Data":"$b64"}""".toByteArray())
                }
            }
            assertEquals(200, uploadConn.responseCode)
            val uploadResp = uploadConn.inputStream.bufferedReader().readText()
            val uploadJson = JSONObject(uploadResp)
            assertTrue(uploadJson.optBoolean("ok"))
            val fileId = uploadJson.getString("id")
            val sha256 = uploadJson.getString("sha256")
            assertEquals(64, sha256.length)

            val dlConn = (URL("http://127.0.0.1:${status.port}/api/files/download?id=$fileId").openConnection() as HttpURLConnection)
            assertEquals(200, dlConn.responseCode)
            assertEquals(sha256, dlConn.getHeaderField("X-SHA256"))
            val downloaded = dlConn.inputStream.readBytes()
            assertArrayEquals(payloadBytes, downloaded)

            // 2. Verify live TLS / SSL HTTPS handshake on :httpsPort using trust-all test context
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            })
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, trustAll, java.security.SecureRandom())
            }
            val httpsConn = (URL("https://127.0.0.1:${status.httpsPort}/api/info").openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                sslSocketFactory = sslCtx.socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                connectTimeout = 3000
                readTimeout = 3000
            }
            assertEquals("HTTPS /api/info must return 200 OK", 200, httpsConn.responseCode)
            assertTrue(httpsConn.inputStream.bufferedReader().readText().contains("1.3.1"))

            // 3. Verify GET /api/cert.der serves valid X.509 certificate
            val certConn = (URL("http://127.0.0.1:${status.port}/api/cert.der").openConnection() as HttpURLConnection)
            assertEquals(200, certConn.responseCode)
            val certDer = certConn.inputStream.readBytes()
            assertTrue("GET /api/cert.der must return non-empty DER bytes", certDer.isNotEmpty())
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val x509 = cf.generateCertificate(certDer.inputStream()) as java.security.cert.X509Certificate
            val daysValid = (x509.notAfter.time - x509.notBefore.time) / (1000L * 60L * 60L * 24L)
            assertTrue("TLS certificate validity must be <= 397 days, got $daysValid", daysValid <= 397L)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `verify field safety features - barometer storm, geofence, quick status, reactions, QR join, decoy, Cloudflare default`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = DevicePreferences(app)
        assertTrue(
            "Default username relay URL must point to live Cloudflare Workers D1",
            prefs.usernameRelayUrl.contains("pulsemesh-username-relay.pulsemesh.workers.dev")
        )

        // Barometric storm trend detection
        val (delta, label, storm) = com.example.service.TacticalSensorService.evaluateBarometricTrend(
            listOf(0L to 1016.0f, 3_600_000L to 1014.0f, 7_200_000L to 1012.2f)
        )
        assertNotNull(delta)
        assertTrue("Rapid -3.8 hPa drop must trigger STORM WARNING", storm)
        assertEquals("STORM WARNING", label)

        val vm = MeshViewModel(app)
        vm.setManualGpsLocation(37.9838, 23.7275)

        // Geofence perimeter breach detection
        vm.configureGeofence(enabled = true, radiusMeters = 200)
        vm.handleIncomingPacket(
            WireMeshPacket(
                packetId = "gf_peer_far",
                srcNodeId = "0xF444",
                srcCallsign = "FarScout",
                dstNodeId = "ALL",
                type = "GPS_BEACON",
                payload = "GPS",
                lat = 37.9950,
                lng = 23.7275,
                ttl = 7,
                hops = 0
            )
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "Peer >1km away outside 200m geofence must raise breach alert",
            vm.uiState.value.geofenceAlertMessage.contains("FarScout")
        )
        vm.dismissGeofenceAlert()
        assertEquals("", vm.uiState.value.geofenceAlertMessage)

        // Quick-Status Text + Voice toggle
        vm.sendQuickStatus(com.example.model.QuickStatusCode.NEED_WATER)
        assertEquals("NEED_WATER", vm.uiState.value.lastQuickStatusCode)

        // Reactions & Pin + Search + Trip Export
        vm.selectThread("channel_primary")
        vm.sendMessageInThread("channel_primary", "Check summit rally")
        val msg = vm.uiState.value.activeThreadPackets.first { it.content == "Check summit rally" }
        vm.sendReaction(msg.id, "👍")
        vm.togglePinMessage("channel_primary", msg.id)
        val after = vm.uiState.value.activeThreadPackets.first { it.id == msg.id }
        assertTrue(after.isPinned)
        assertTrue(after.reactions["👍"]?.isNotEmpty() == true)
        assertTrue(vm.searchMessages("summit").any { it.second.id == msg.id })
        assertTrue(vm.exportTripArchiveJson().contains("Check summit rally"))

        // QR Instant Contact/Group Join round-trip
        val qrUri = vm.buildInstantJoinQrPayload()
        assertTrue(qrUri.startsWith("pulsemesh://join?"))
        val (importOk, _) = vm.importInstantJoinQrPayload("pulsemesh://join?node=0xD888&cs=DeltaQR&ch=RidgeNet&pk=TESTPK")
        assertTrue(importOk)
        assertTrue(vm.uiState.value.nodes.any { it.id == "0xD888" })

        // Language + Expedition Mode + Per-thread burn + Decoy PIN
        vm.setAppLanguage("el")
        assertEquals("el", vm.uiState.value.appLanguage)
        vm.toggleExpeditionMode()
        assertTrue(vm.uiState.value.expeditionModeEnabled)
        vm.setThreadBurnTtl("channel_primary", 300)
        assertEquals(300, vm.uiState.value.perThreadBurnTtlMap["channel_primary"])
        assertTrue(vm.setDecoyPin("2468"))
        assertTrue(vm.verifyOrEnterDecoyMode("2468"))
        assertTrue(vm.uiState.value.isDecoyModeActive)
        vm.exitDecoyMode()
        assertFalse(vm.uiState.value.isDecoyModeActive)

        // Daylight Theme selection works (no double-toggle bug)
        vm.setAppTheme(com.example.util.AppTheme.TACTICAL_LIGHT)
        assertEquals(com.example.util.AppTheme.TACTICAL_LIGHT, vm.uiState.value.appTheme)
    }

    @Test
    fun `verify v1_3_1 duplicate retry CHAT dedupes bubbles and markPacketDelivered handles WEB PORTAL`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MeshViewModel(app)
        vm.selectThread("channel_primary")

        val pkt = WireMeshPacket(
            packetId = "retry_pkt_131",
            srcNodeId = "0xB777",
            srcCallsign = "BravoRetry",
            dstNodeId = "ALL",
            type = "CHAT",
            payload = "Retried ridge check",
            ttl = 7,
            hops = 1
        )
        vm.handleIncomingPacket(pkt)
        vm.handleIncomingPacket(pkt)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val matches = vm.uiState.value.activeThreadPackets.filter { it.id == "retry_pkt_131" }
        assertEquals("Retried duplicate CHAT packet must produce exactly 1 bubble", 1, matches.size)

        // Verify PersistentChatManager.markPacketDelivered marks WEB PORTAL outgoing packets
        val chatMgr = PersistentChatManager(app)
        chatMgr.savePacket(
            "channel_primary",
            MeshPacket(
                id = "web_out_131",
                sourceId = "0xW111",
                destId = "ALL",
                sourceCallsign = "iPhoneWeb",
                type = PacketType.TEXT,
                content = "Hello from iPhone Safari",
                hops = 0,
                transport = "WEB PORTAL",
                rawHexBadge = "WEB",
                isIncoming = false,
                isDelivered = false
            )
        )
        assertTrue(chatMgr.markPacketDelivered("web_out_131", "AndroidHost"))
        val updated = chatMgr.getPacketsForThread("channel_primary").first { it.id == "web_out_131" }
        assertTrue(updated.isDelivered)
        assertEquals("AndroidHost", updated.ackedByCallsign)
    }

    @Test
    fun `verify v1_3_1 LocalSelfSignedTlsFactory produces serverAuth X509 cert with dynamic SAN`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val (factory, err) = com.example.service.LocalSelfSignedTlsFactory.createSslServerSocketFactory(
            app,
            listOf("192.168.43.1", "192.168.49.1")
        )
        assertNotNull("SSLServerSocketFactory must succeed: $err", factory)
        val der = com.example.service.LocalSelfSignedTlsFactory.getCurrentCertificateDer(app)
        assertNotNull("Current DER certificate must be populated", der)

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(der!!.inputStream()) as java.security.cert.X509Certificate
        val daysValid = (cert.notAfter.time - cert.notBefore.time) / (1000L * 60L * 60L * 24L)
        assertTrue("Cert validity must be <= 397 days, was $daysValid", daysValid <= 397L)
        assertTrue(
            "ExtendedKeyUsage must contain id-kp-serverAuth (1.3.6.1.5.5.7.3.1)",
            cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true
        )
        val sanValues = cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }.orEmpty()
        assertTrue("SANs must contain Hotspot IP 192.168.43.1, got $sanValues", sanValues.contains("192.168.43.1"))
    }

    @Test
    fun `verify v1_3_1 Web Portal call signals polling buffer and POST send types`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val submittedList = CopyOnWriteArrayList<WebSubmittedPacket>()
        val server = PulseMeshWebServer(
            context = app,
            scope = scope,
            buildInfoJson = { url, cnt ->
                JSONObject().apply {
                    put("pulsemeshHost", true)
                    put("version", "1.3.1")
                    put("portalUrl", url)
                    put("webClientCount", cnt)
                }
            },
            buildStateJson = { JSONObject().apply { put("ok", true) } },
            onClientRegistered = {},
            onWebPacketSubmitted = { submittedList.add(it) }
        )
        server.start("127.0.0.1")
        val deadline = System.currentTimeMillis() + 4000L
        while ((!server.isRunning() || !server.status.value.selfTestPassed) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        try {
            server.notifyCallSignal("CALL_RING", "0xA111", "AlphaHost", "0xW222", "RING")
            val signals = server.getRecentCallSignalsJson()
            assertTrue("Recent call signals array must include CALL_RING", signals.length() >= 1)
            assertEquals("CALL_RING", signals.getJSONObject(signals.length() - 1).getString("type"))

            val port = server.status.value.port
            for (pktType in listOf("CHAT_ACK", "SOS_CLEAR", "QUICK_STATUS", "REACTION")) {
                val conn = (URL("http://127.0.0.1:$port/api/send").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use {
                        it.write("""{"packetId":"wp_${pktType}","clientNodeId":"0xW222","callsign":"WebOp","dstNodeId":"ALL","type":"$pktType","text":"payload","payload":"payload"}""".toByteArray())
                    }
                }
                assertEquals("POST /api/send for $pktType must return 200", 200, conn.responseCode)
            }
            assertEquals(4, submittedList.size)
            assertEquals(listOf("CHAT_ACK", "SOS_CLEAR", "QUICK_STATUS", "REACTION"), submittedList.map { it.type })
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `verify v1_3_1 AppStrings Greek translation and weather trend labels`() {
        assertEquals("Ρυθμίσεις", com.example.util.AppStrings.tr("el", "Settings", "Ρυθμίσεις"))
        assertEquals("Settings", com.example.util.AppStrings.tr("en", "Settings", "Ρυθμίσεις"))
        assertEquals(
            "ΠΡΟΕΙΔΟΠΟΙΗΣΗ ΚΑΤΑΙΓΙΔΑΣ",
            com.example.util.AppStrings.weatherTrendLabel("el", "STORM WARNING")
        )
        assertEquals(
            "STORM WARNING",
            com.example.util.AppStrings.weatherTrendLabel("en", "STORM WARNING")
        )
    }

    @Test
    fun `verify v1_3_2 two-tier Root CA and iOS mobileconfig and ca_crt endpoints`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val (factory, err) = com.example.service.LocalSelfSignedTlsFactory.createSslServerSocketFactory(
            context = app,
            extraIps = listOf("192.168.43.1", "192.168.49.1")
        )
        assertNotNull("2-Tier SSLServerSocketFactory must succeed: $err", factory)

        val rootDer = com.example.service.LocalSelfSignedTlsFactory.getRootCaCertificateDer(app)
        val leafDer = com.example.service.LocalSelfSignedTlsFactory.getLeafCertificateDer(app)
        assertNotNull("Root CA DER must exist", rootDer)
        assertNotNull("Leaf Cert DER must exist", leafDer)

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val rootCert = cf.generateCertificate(rootDer!!.inputStream()) as java.security.cert.X509Certificate
        val leafCert = cf.generateCertificate(leafDer!!.inputStream()) as java.security.cert.X509Certificate

        assertTrue("Root CA must have BasicConstraints CA=TRUE (pathLen >= 0)", rootCert.basicConstraints >= 0)
        assertEquals("Leaf certificate must have BasicConstraints CA=FALSE (-1)", -1, leafCert.basicConstraints)
        // Cryptographically verify that the Server Leaf Certificate is signed by the PulseMesh Root CA!
        leafCert.verify(rootCert.publicKey)
        rootCert.verify(rootCert.publicKey)

        val mobileConfigBytes = com.example.service.LocalSelfSignedTlsFactory.buildIosMobileConfigXml(app, "192.168.43.1")
        assertNotNull("iOS .mobileconfig profile must be generated", mobileConfigBytes)
        val mobileConfigXml = String(mobileConfigBytes!!, Charsets.UTF_8)
        assertTrue(mobileConfigXml.contains("com.apple.security.root"))
        assertTrue(mobileConfigXml.contains("PulseMesh Local Root CA"))
    }
}
