// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.net.Network
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure-Kotlin RFC 6455 TLS WebSocket Client for Cloudflare Durable Objects (`PulseMeshRealtimeHub`).
 *
 * Replaces `ntfy.sh` 8–12 HTTP-POST/sec polling & rate-limit collisions with a single persistent
 * bidirectional WebSocket supporting:
 * - Instant `INBOX_WAKEUP` notifications
 * - Low-latency WebRTC SDP/ICE and Call Control signaling
 * - Binary end-to-end encrypted voice frames
 */
class CloudflareRealtimeClient(
    private val scope: CoroutineScope,
    private val isInternetEnabled: () -> Boolean,
    private val activeRelayUrl: () -> String,
    private val localUsername: () -> String,
    private val authToken: () -> String,
    private val boundNetwork: () -> Network? = { null },
    private val onTextMessage: suspend (String) -> Unit,
    private val onBinaryMessage: suspend (ByteArray) -> Unit
) {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val secureRandom = SecureRandom()
    private var readerJob: Job? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    private val writeLock = Any()

    @Synchronized
    fun startOrReconnect(room: String = "") {
        stop()
        if (!isInternetEnabled()) return
        val baseUrl = activeRelayUrl().trim().trimEnd('/')
        val uname = com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager.normalizeUsername(localUsername())
        val token = authToken().trim()
        if (baseUrl.isEmpty() || uname.length < 3 || token.isBlank()) return

        readerJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 1_500L
            while (isActive && isInternetEnabled()) {
                val ok = runSessionOnce(baseUrl, uname, token, room.ifBlank { "inbox:$uname" })
                if (ok) {
                    backoffMs = 1_500L
                } else {
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
                if (!isActive || !isInternetEnabled()) break
                delay(backoffMs)
            }
            _isConnected.value = false
        }
    }

    @Synchronized
    fun stop() {
        readerJob?.cancel()
        readerJob = null
        closeActiveSocket()
        _isConnected.value = false
    }

    fun sendText(text: String): Boolean {
        if (!isInternetEnabled() || !_isConnected.value) return false
        return writeFrame(opcode = 0x1, payload = text.toByteArray(Charsets.UTF_8))
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        if (!isInternetEnabled() || !_isConnected.value) return false
        return writeFrame(opcode = 0x2, payload = bytes)
    }

    private fun closeActiveSocket() {
        synchronized(writeLock) {
            runCatching { activeOutput?.close() }
            runCatching { activeSocket?.close() }
            activeOutput = null
            activeSocket = null
        }
        _isConnected.value = false
    }

    private suspend fun runSessionOnce(
        baseUrl: String,
        username: String,
        token: String,
        room: String
    ): Boolean {
        var connectedOnce = false
        try {
            val parsed = URL(baseUrl)
            val isTls = parsed.protocol.equals("https", ignoreCase = true)
            val host = parsed.host
            val port = if (parsed.port > 0) parsed.port else if (isTls) 443 else 80
            val basePath = parsed.path.trimEnd('/')
            val encUser = URLEncoder.encode(username, "UTF-8")
            val encRoom = URLEncoder.encode(room, "UTF-8")
            val encTok = URLEncoder.encode(token, "UTF-8")
            val pathWithQuery = "$basePath/v1/realtime/ws?username=$encUser&room=$encRoom&token=$encTok"

            val rawSocket = Socket()
            val net = boundNetwork()
            if (net != null) {
                runCatching { net.bindSocket(rawSocket) }
            }
            rawSocket.connect(InetSocketAddress(host, port), 8_000)
            rawSocket.soTimeout = 45_000

            val socket: Socket = if (isTls) {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                    startHandshake()
                }
            } else {
                rawSocket
            }

            activeSocket = socket
            val out = socket.getOutputStream()
            activeOutput = out
            val inp = BufferedInputStream(socket.getInputStream(), 16_384)

            val wsKeyBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
            val wsKey = Base64.encodeToString(wsKeyBytes, Base64.NO_WRAP)
            val hostHeader = if ((isTls && port == 443) || (!isTls && port == 80)) host else "$host:$port"
            val req = buildString {
                append("GET ").append(pathWithQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("X-PulseMesh-Auth: ").append(token).append("\r\n")
                append("\r\n")
            }
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val headerLines = mutableListOf<String>()
            while (true) {
                val line = readHttpLine(inp) ?: throw EOFException("Handshake EOF")
                if (line.isEmpty()) break
                headerLines.add(line)
            }
            val statusLine = headerLines.firstOrNull().orEmpty()
            if (!statusLine.contains(" 101 ")) {
                closeActiveSocket()
                return false
            }

            val expectedAccept = computeWsAcceptKey(wsKey)
            val acceptHeader = headerLines
                .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            if (acceptHeader != expectedAccept) {
                closeActiveSocket()
                return false
            }

            connectedOnce = true
            _isConnected.value = true

            while (isInternetEnabled()) {
                val b0 = inp.read()
                if (b0 < 0) break
                val b1 = inp.read()
                if (b1 < 0) break

                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    val h = inp.read()
                    val l = inp.read()
                    if (h < 0 || l < 0) break
                    len = ((h shl 8) or l).toLong()
                } else if (len == 127L) {
                    var extended = 0L
                    for (i in 0 until 8) {
                        val b = inp.read()
                        if (b < 0) throw EOFException("Length EOF")
                        extended = (extended shl 8) or (b.toLong() and 0xFFL)
                    }
                    len = extended
                }
                if (len < 0 || len > 4L * 1024L * 1024L) break

                val maskKey = if (masked) ByteArray(4).also { readFully(inp, it) } else null
                val payload = ByteArray(len.toInt())
                readFully(inp, payload)
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x1 -> if (fin) onTextMessage(String(payload, Charsets.UTF_8))
                    0x2 -> if (fin) onBinaryMessage(payload)
                    0x8 -> break // Close frame
                    0x9 -> writeFrame(0xA, payload) // Pong
                }
            }
        } catch (_: Exception) {
            // Connection dropped or unreachable; outer loop applies backoff
        } finally {
            closeActiveSocket()
        }
        return connectedOnce
    }

    private fun writeFrame(opcode: Int, payload: ByteArray): Boolean {
        val out = activeOutput ?: return false
        return try {
            val mask = ByteArray(4).also { secureRandom.nextBytes(it) }
            val len = payload.size
            val headerSize = when {
                len <= 125 -> 6
                len <= 65535 -> 8
                else -> 14
            }
            val frame = ByteArray(headerSize + len)
            frame[0] = (0x80 or (opcode and 0x0F)).toByte()
            var offset: Int
            when {
                len <= 125 -> {
                    frame[1] = (0x80 or len).toByte()
                    offset = 2
                }
                len <= 65535 -> {
                    frame[1] = (0x80 or 126).toByte()
                    frame[2] = ((len ushr 8) and 0xFF).toByte()
                    frame[3] = (len and 0xFF).toByte()
                    offset = 4
                }
                else -> {
                    frame[1] = (0x80 or 127).toByte()
                    val longLen = len.toLong()
                    for (i in 0 until 8) {
                        frame[2 + i] = ((longLen ushr (56 - i * 8)) and 0xFFL).toByte()
                    }
                    offset = 10
                }
            }
            System.arraycopy(mask, 0, frame, offset, 4)
            offset += 4
            for (i in payload.indices) {
                frame[offset + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            synchronized(writeLock) {
                out.write(frame)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readHttpLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prevCr = false
        while (true) {
            val ch = inp.read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) {
                if (prevCr && sb.isNotEmpty()) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            prevCr = (ch == '\r'.code)
            sb.append(ch.toChar())
        }
    }

    private fun readFully(inp: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = inp.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("Unexpected stream end")
            offset += read
        }
    }

    private fun computeWsAcceptKey(wsKey: String): String {
        val combined = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val digest = MessageDigest.getInstance("SHA-1").digest(combined.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
