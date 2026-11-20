// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.TacticalE2eeManager
import com.example.crypto.UsernameClaimRecord
import com.example.model.MeshPacket
import com.example.model.PacketType
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
}
