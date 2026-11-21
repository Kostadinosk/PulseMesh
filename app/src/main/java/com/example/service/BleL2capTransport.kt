// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE L2CAP Connection-Oriented Channel (CoC) High-Throughput Transport (Android 10+ / API 29+).
 *
 * Bypasses GATT 20/160-byte attribute chunking by opening a native credit-based streaming
 * `BluetoothSocket` (`listenUsingInsecureL2capChannel` / `createInsecureL2capChannel(psm)`)
 * directly over BLE 2M / Coded PHY.
 * - Dynamic PSM is advertised in outgoing JSON frames (`"l2psm"`).
 * - When a peer reports its `"l2psm"`, this engine automatically connects an outbound L2CAP CoC socket.
 * - Carries both low-latency call signaling (`CALL_ACCEPT`, `CALL_ACCEPT_ACK`, `CALL_RING`, `CALL_END`)
 *   and 160ms G.711 µ-Law voice frames without GATT write-callback serialization bottlenecks.
 */
class BleL2capTransport(
    private val scope: CoroutineScope,
    private val bluetoothAdapterProvider: () -> BluetoothAdapter?,
    private val isBleReady: () -> Boolean,
    private val onJsonLineReceived: (line: String, device: BluetoothDevice?) -> Unit,
    private val onActiveChannelsChanged: (count: Int, localPsm: Int) -> Unit = { _, _ -> }
) {
    @Volatile
    var localPsm: Int = 0
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val pendingConnects: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val connectedDeviceCount: Int
        get() = activeSockets.count { it.value.isConnected }

    fun hasActiveChannel(): Boolean = activeSockets.values.any { it.isConnected }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (acceptJob?.isActive == true && serverSocket != null && localPsm > 0) return
        if (!isBleReady()) return

        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val adapter = bluetoothAdapterProvider() ?: return@launch
                val srv = adapter.listenUsingInsecureL2capChannel()
                serverSocket = srv
                localPsm = srv.psm
                onActiveChannelsChanged(connectedDeviceCount, localPsm)

                while (isActive) {
                    val sock = runCatching { srv.accept() }.getOrNull() ?: break
                    val addr = sock.remoteDevice?.address ?: "l2cap_${System.currentTimeMillis()}"
                    registerSocket(addr, sock)
                }
            }.onFailure {
                localPsm = 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeerIfNeeded(device: BluetoothDevice, remotePsm: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (remotePsm <= 0 || !isBleReady()) return
        val addr = device.address ?: return
        val existing = activeSockets[addr]
        if (existing != null && existing.isConnected) return
        if (!pendingConnects.add(addr)) return

        scope.launch(Dispatchers.IO) {
            try {
                val sock = device.createInsecureL2capChannel(remotePsm)
                withTimeoutOrNull(2500L) {
                    runCatching { sock.connect() }
                }
                if (sock.isConnected) {
                    registerSocket(addr, sock)
                } else {
                    runCatching { sock.close() }
                }
            } catch (_: Throwable) {
                // Ignore transient L2CAP connect error; GATT remains active
            } finally {
                pendingConnects.remove(addr)
            }
        }
    }

    private fun registerSocket(addr: String, socket: BluetoothSocket) {
        val old = activeSockets.put(addr, socket)
        if (old != null && old !== socket) {
            runCatching { old.close() }
        }
        onActiveChannelsChanged(connectedDeviceCount, localPsm)

        scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (isActive && socket.isConnected) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                        CompactMeshProtocol.isCompactLine(trimmed)
                    ) {
                        onJsonLineReceived(trimmed, socket.remoteDevice)
                    }
                }
            } catch (_: Throwable) {
                // Socket closed
            } finally {
                activeSockets.remove(addr, socket)
                runCatching { socket.close() }
                onActiveChannelsChanged(connectedDeviceCount, localPsm)
            }
        }
    }

    /**
     * Broadcasts a compact `PMB2:` frame (or JSON fallback) across all connected BLE L2CAP CoC sockets.
     */
    fun sendCompactOrJson(packet: WireMeshPacket, useCompactBinary: Boolean): Boolean {
        val line = if (useCompactBinary) {
            CompactMeshProtocol.encodeCompactLine(packet)
        } else {
            CompactMeshProtocol.toCanonicalJson(packet)
        }
        return sendJsonLine(line)
    }

    /**
     * Broadcasts a newline-framed JSON line across all connected BLE L2CAP CoC sockets.
     * Returns true if at least one L2CAP socket accepted the frame.
     */
    fun sendJsonLine(jsonString: String): Boolean {
        if (activeSockets.isEmpty()) return false
        val clean = jsonString.trim()
        if (clean.isEmpty()) return false
        val bytes = ("\n" + clean + "\n").toByteArray(Charsets.UTF_8)
        var sentAny = false

        for ((addr, sock) in activeSockets) {
            if (!sock.isConnected) {
                activeSockets.remove(addr, sock)
                continue
            }
            val ok = runCatching {
                val out: OutputStream = sock.outputStream
                synchronized(out) {
                    out.write(bytes)
                    out.flush()
                }
                true
            }.getOrDefault(false)
            if (ok) {
                sentAny = true
            } else {
                activeSockets.remove(addr, sock)
                runCatching { sock.close() }
            }
        }
        return sentAny
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        localPsm = 0
        runCatching { serverSocket?.close() }
        serverSocket = null
        for ((_, sock) in activeSockets) {
            runCatching { sock.close() }
        }
        activeSockets.clear()
        pendingConnects.clear()
        onActiveChannelsChanged(0, 0)
    }
}
