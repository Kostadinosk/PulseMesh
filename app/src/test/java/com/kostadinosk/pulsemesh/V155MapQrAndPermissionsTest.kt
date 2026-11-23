// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.kostadinosk.pulsemesh.map.AtakCotInterop
import com.kostadinosk.pulsemesh.map.GpxKmlInterop
import com.kostadinosk.pulsemesh.map.MapCoordinateMath
import com.kostadinosk.pulsemesh.map.OfflineMapPackManager
import com.kostadinosk.pulsemesh.model.BreadcrumbPoint
import com.kostadinosk.pulsemesh.model.MeshNode
import com.kostadinosk.pulsemesh.model.TacticalWaypoint
import com.kostadinosk.pulsemesh.model.WaypointType
import com.kostadinosk.pulsemesh.service.MeshForegroundRelayService
import com.kostadinosk.pulsemesh.service.NfcPairingPayloadStore
import com.kostadinosk.pulsemesh.service.NfcPairingProtocol
import com.kostadinosk.pulsemesh.service.PulseMeshWebServer
import com.kostadinosk.pulsemesh.ui.components.TacticalMap
import com.kostadinosk.pulsemesh.ui.screens.SettingsScreen
import com.kostadinosk.pulsemesh.ui.theme.PulseMeshTheme
import com.kostadinosk.pulsemesh.util.AppTheme
import com.kostadinosk.pulsemesh.util.QrCodeGenerator
import com.kostadinosk.pulsemesh.util.QrPairingAction
import com.kostadinosk.pulsemesh.util.QrPairingRouter
import com.kostadinosk.pulsemesh.viewmodel.MeshUiState
import com.kostadinosk.pulsemesh.viewmodel.MeshViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V155MapQrAndPermissionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `fgs relay service gates on granted permissions and partial permissions never crash`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = Shadows.shadowOf(app)

        // Fresh install: zero permissions granted -> must skip startForegroundService()
        shadowApp.denyPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MANAGE_OWN_CALLS
        )
        assertEquals(0, MeshForegroundRelayService.computeAllowedFgsTypes(app, activeVoiceCall = false))
        assertFalse(MeshForegroundRelayService.hasAnyPermittedFgsType(app, activeVoiceCall = false))

        MeshForegroundRelayService.startOrUpdate(app, peerCount = 0, dtnCount = 0)
        assertNull("startForegroundService must not fire without permissions", shadowApp.nextStartedService)

        // Direct onStartCommand with 0 permissions stops itself cleanly instead of ForegroundServiceDidNotStartInTimeException
        val serviceController = Robolectric.buildService(
            MeshForegroundRelayService::class.java,
            Intent(app, MeshForegroundRelayService::class.java).apply {
                action = MeshForegroundRelayService.ACTION_START_OR_UPDATE
            }
        ).create().startCommand(0, 0)
        assertFalse(MeshForegroundRelayService.isRunning)
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf)

        // Grant only BLE scan -> CONNECTED_DEVICE permitted, MICROPHONE/PHONE_CALL still gated off
        shadowApp.grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val radioOnlyTypes = MeshForegroundRelayService.computeAllowedFgsTypes(app, activeVoiceCall = true)
        assertTrue((radioOnlyTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0)
        assertEquals(0, radioOnlyTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertEquals(0, radioOnlyTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)

        // Grant mic + manage calls -> voice FGS types enabled
        shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MANAGE_OWN_CALLS)
        val fullTypes = MeshForegroundRelayService.computeAllowedFgsTypes(app, activeVoiceCall = true)
        assertTrue((fullTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0)
        assertTrue((fullTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) != 0)

        // Partial permission map on MeshViewModel must not crash and updates capability flags accurately
        shadowApp.denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val vm = MeshViewModel(app)
        vm.onPermissionsResult(
            mapOf(
                Manifest.permission.BLUETOOTH_SCAN to true,
                Manifest.permission.RECORD_AUDIO to false,
                Manifest.permission.ACCESS_FINE_LOCATION to false
            )
        )
        assertFalse(vm.uiState.value.hasRequiredPermissions)
        assertTrue(vm.uiState.value.showPermissionsBanner)

        vm.onPermissionsResult(
            mapOf(
                Manifest.permission.BLUETOOTH_SCAN to true,
                Manifest.permission.RECORD_AUDIO to true,
                Manifest.permission.ACCESS_FINE_LOCATION to true
            )
        )
        assertTrue(vm.uiState.value.hasRequiredPermissions)
        assertFalse(vm.uiState.value.showPermissionsBanner)
    }

    @Test
    fun `web mercator inverse math, GPX KML ATAK CoT, and MBTiles pack manager roundtrip accurately`() {
        // 1. Exact inverse Mercator at high northern latitude (68.5 N)
        val lat = 68.51234
        val lon = 24.78912
        val zoom = 14.5
        val (tx, ty) = MapCoordinateMath.latLonToTile(lat, lon, zoom)
        val (rLat, rLon) = MapCoordinateMath.tileToLatLon(tx, ty, zoom)
        assertEquals(lat, rLat, 1e-4)
        assertEquals(lon, rLon, 1e-4)

        val (offLat, offLon) = MapCoordinateMath.pixelOffsetToLatLon(lat, lon, 14.0f, 128f, -256f)
        assertTrue("Moving negative screen Y (north) increases latitude", offLat > lat)
        assertTrue("Moving positive screen X (east) increases longitude", offLon > lon)

        val dist = MapCoordinateMath.haversineDistanceMeters(37.9838, 23.7275, 37.9938, 23.7275)
        assertTrue(dist in 1050.0..1150.0)
        val brg = MapCoordinateMath.initialBearingDegrees(37.9838, 23.7275, 37.9938, 23.7275)
        assertEquals("N", MapCoordinateMath.cardinalDirection(brg))

        // 2. GPX 1.1 & KML 2.2 roundtrip
        val wp = TacticalWaypoint("wp_1", "Alpha & <Camp>", WaypointType.SUPPLY, 37.981, 23.731, "Lead", 1700000000000L, "GPX")
        val track = listOf(BreadcrumbPoint(37.980, 23.730, 120.0), BreadcrumbPoint(37.981, 23.731, 135.0))
        val gpx = GpxKmlInterop.exportGpx(listOf(wp), mapOf("Patrol" to track))
        val gpxParsed = GpxKmlInterop.importGpx(gpx)
        assertEquals(1, gpxParsed.waypoints.size)
        assertEquals("Alpha & <Camp>", gpxParsed.waypoints[0].title)
        assertEquals(2, gpxParsed.trackPoints.size)

        val kml = GpxKmlInterop.exportKml(listOf(wp), mapOf("Patrol" to track))
        val kmlParsed = GpxKmlInterop.importKml(kml)
        assertEquals(1, kmlParsed.waypoints.size)
        assertEquals(WaypointType.SUPPLY, kmlParsed.waypoints[0].type)
        assertEquals(2, kmlParsed.trackPoints.size)

        // 3. ATAK CoT XML encode + parse
        val node = MeshNode("0x123456789ABCDEF0", "Ranger-1", latitude = 37.99, longitude = 23.75, altitudeM = 210.0)
        val nodeCot = AtakCotInterop.encodeNodeToCotXml(node)
        val parsedCot = AtakCotInterop.parseCotXml(nodeCot)
        assertNotNull(parsedCot)
        assertEquals("a-f-G-U-C", parsedCot!!.type)
        assertEquals("Ranger-1", parsedCot.callsign)
        assertEquals(37.99, parsedCot.lat, 1e-4)

        val sosCot = AtakCotInterop.encodeWaypointToCotXml(wp.copy(type = WaypointType.SOS))
        assertEquals("b-a-o-tbl", AtakCotInterop.parseCotXml(sosCot)!!.type)

        // 4. MBTiles SQLite pack inspect + XYZ->TMS tile read + ZIP P2P export/import
        val app = ApplicationProvider.getApplicationContext<Application>()
        val packsDir = OfflineMapPackManager.getPacksDirectory(app)
        packsDir.listFiles()?.forEach { it.delete() }
        val mbFile = File(packsDir, "athens_test.mbtiles")
        val tilePngPayload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        SQLiteDatabase.openOrCreateDatabase(mbFile, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES ('minzoom', '10')")
            db.execSQL("INSERT INTO metadata VALUES ('maxzoom', '16')")
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            // z=2, x=1, yXyz=1 -> tmsY = (1 shl 2) - 1 - 1 = 2
            db.execSQL("INSERT INTO tiles VALUES (2, 1, 2, ?)", arrayOf<Any>(tilePngPayload))
        }
        val info = OfflineMapPackManager.inspectPackFile(mbFile)
        assertNotNull(info)
        assertEquals("MBTILES", info!!.format)
        assertEquals(10, info.zoomMin)
        assertEquals(16, info.zoomMax)
        assertArrayEquals(tilePngPayload, OfflineMapPackManager.readMbtilesTile(mbFile, z = 2, x = 1, yXyz = 1))

        val zipFile = File(app.cacheDir, "packs_bundle.zip")
        OfflineMapPackManager.exportMapPackZip(app, zipFile)
        mbFile.delete()
        assertEquals(1, OfflineMapPackManager.importMapPackZip(app, zipFile))
        assertTrue(File(packsDir, "athens_test.mbtiles").exists())
    }

    @Test
    fun `zxing qr pairing router, long invite qr bitmap, nfc hce apdu, and web server apk endpoints work`() {
        val peerPayload = "pulsemesh://peer?id=0xABCDEF1234567890&callsign=Scout-7&pub=TESTPUBKEYBASE64&user=scout7"
        val bmp = QrCodeGenerator.generateBitmap(peerPayload, pixelSize = 320)
        assertNotNull("ZXing fallback should encode long URIs", bmp)
        val decodedText = QrPairingRouter.decodeQrFromBitmap(bmp!!)
        assertEquals(peerPayload, decodedText)

        val peerAction = QrPairingRouter.parsePayload(decodedText!!) as QrPairingAction.AddContact
        assertEquals("0xABCDEF1234567890", peerAction.nodeId)
        assertEquals("Scout-7", peerAction.callsign)
        assertEquals("scout7", peerAction.username)

        val wifiAction = QrPairingRouter.parsePayload("WIFI:T:WPA;S:Pulse\\;Mesh;P:sec\\:ret;;") as QrPairingAction.JoinWifiHotspot
        assertEquals("Pulse;Mesh", wifiAction.ssid)
        assertEquals("sec:ret", wifiAction.passphrase)

        val grpJson = """{"type":"pmGroupInvite","groupId":"grp_99","groupName":"Summit","groupKeyBase64":"KEY99==","keyEpoch":3,"memberIds":["0xA","0xB"]}"""
        val grpAction = QrPairingRouter.parsePayload(grpJson) as QrPairingAction.JoinGroup
        assertEquals("grp_99", grpAction.groupId)
        assertEquals(3, grpAction.keyEpoch)
        assertEquals(listOf("0xA", "0xB"), grpAction.memberIds)

        // NFC HCE APDU handshake
        NfcPairingPayloadStore.activePairingPayload = peerPayload
        assertArrayEquals(NfcPairingProtocol.SW_OK, NfcPairingProtocol.processCommandApdu(NfcPairingProtocol.buildSelectAidCommand()))
        val identityResp = NfcPairingProtocol.processCommandApdu(NfcPairingProtocol.CMD_GET_IDENTITY)
        assertEquals(peerPayload, NfcPairingProtocol.extractPayloadIfOk(identityResp))
        NfcPairingPayloadStore.activePairingPayload = ""

        // Embedded Web Server /api/apk/info + /ca.mobileconfig
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = PulseMeshWebServer(
            context = app,
            scope = scope,
            buildInfoJson = { url, cnt -> JSONObject().put("portalUrl", url).put("webClients", cnt) },
            buildStateJson = { JSONObject() },
            onClientRegistered = {},
            onWebPacketSubmitted = {},
            canFetchOnlineTiles = { false }
        )
        try {
            server.start()
            val startWait = System.currentTimeMillis()
            while (!server.status.value.isRunning && System.currentTimeMillis() - startWait < 3000) {
                Thread.sleep(25)
            }
            val port = server.status.value.port
            val infoConn = URL("http://127.0.0.1:$port/api/apk/info").openConnection() as HttpURLConnection
            assertEquals(200, infoConn.responseCode)
            val infoObj = JSONObject(infoConn.inputStream.bufferedReader().readText())
            assertEquals(BuildConfig.VERSION_NAME, infoObj.getString("versionName"))
            assertEquals(BuildConfig.FLAVOR_TIER, infoObj.getString("flavor"))

            val cfgConn = URL("http://127.0.0.1:$port/ca.mobileconfig").openConnection() as HttpURLConnection
            assertEquals(200, cfgConn.responseCode)
            val plist = cfgConn.inputStream.bufferedReader().readText()
            assertTrue(plist.contains("com.apple.security.root"))
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `tactical map separates waypoint drop from manual gps override and settings defaults to optional username claim`() {
        var manualOverrideCalls = 0
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.TACTICAL_DARK) {
                TacticalMap(
                    userLat = 37.9838,
                    userLon = 23.7275,
                    isGpsLocked = true,
                    nodes = emptyList(),
                    selectedNode = null,
                    onSelectNode = {},
                    onSetManualLocation = { _, _ -> manualOverrideCalls++ },
                    onRequestGps = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_layer_selector").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("map_ruler_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_compass_rotate_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_gpx_export_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_cot_toggle").assertIsDisplayed()

        // Tapping drop waypoint opens modal with explicit Manual GPS Override & Ruler Point actions
        composeTestRule.onNodeWithTag("map_drop_waypoint_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_set_manual_gps_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_set_ruler_point_btn").assertIsDisplayed()
        assertEquals("Opening pin modal must not overwrite GPS automatically", 0, manualOverrideCalls)

        composeTestRule.onNodeWithTag("map_set_manual_gps_btn").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, manualOverrideCalls)
    }

    @Test
    fun `settings screen shows optional recommended username state and opens claim mode by default`() {
        composeTestRule.setContent {
            PulseMeshTheme(appTheme = AppTheme.TACTICAL_DARK) {
                SettingsScreen(
                    state = MeshUiState(claimedUsername = ""),
                    onToggleSimpleMode = {},
                    onToggleTransportMode = {},
                    onSelectPhoneProfile = {},
                    onAutoMatchHardware = {},
                    onRegenerateNodeId = {},
                    onUpdateCallsign = {},
                    onUpdateMeshChannel = {},
                    onPingMeshNow = {},
                    onRequestGpsPermission = {},
                    onSetManualLocation = { _, _ -> },
                    onOpenSetupWizard = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_lane_identity").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Optional @username (Recommended)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Not set yet (Optional, recommended)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_username_account_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("RESERVE UNIQUE @USERNAME").assertIsDisplayed()
    }
}
