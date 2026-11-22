// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code Generator (Byte mode, ECC Level L, Versions 1..6, up to 134 bytes).
 * Zero external dependencies so PulseMesh remains 100% F-Droid FOSS compatible while producing
 * crisp, camera-scannable QR codes for iPhone/iOS Wi-Fi Direct join & Web Portal URLs.
 */
object QrCodeGenerator {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalDataCodewords: Int,
        val eccPerBlock: Int,
        val numBlocks: Int,
        val alignCoord: Int
    )

    // ISO/IEC 18004 Table 9 — ECC Level L (Versions 1 to 6)
    private val SPECS = listOf(
        VersionSpec(1, 21, 19, 7, 1, -1),
        VersionSpec(2, 25, 34, 10, 1, 18),
        VersionSpec(3, 29, 55, 15, 1, 22),
        VersionSpec(4, 33, 80, 20, 1, 26),
        VersionSpec(5, 37, 108, 26, 1, 30),
        VersionSpec(6, 41, 136, 18, 2, 34)
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val root = GF_EXP[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
        }
        return poly
    }

    private fun rsComputeRemainder(data: ByteArray, eccCount: Int): ByteArray {
        val gen = rsGeneratorPoly(eccCount)
        val rem = IntArray(eccCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, eccCount - 1)
            rem[eccCount - 1] = 0
            if (factor != 0) {
                for (i in 0 until eccCount) {
                    rem[i] = rem[i] xor gfMul(gen[i + 1], factor)
                }
            }
        }
        return ByteArray(eccCount) { rem[it].toByte() }
    }

    /**
     * Escapes special characters for standard iOS/Android `WIFI:T:WPA;S:...;P:...;;` QR payloads.
     */
    fun buildWifiJoinPayload(ssid: String, passphrase: String): String {
        fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
        return if (passphrase.isBlank()) {
            "WIFI:T:nopass;S:${esc(ssid)};;"
        } else {
            "WIFI:T:WPA;S:${esc(ssid)};P:${esc(passphrase)};;"
        }
    }

    /**
     * Encodes UTF-8 text into a boolean QR module matrix (`true` = dark module).
     */
    fun encodeMatrix(text: String): Array<BooleanArray>? {
        val payload = text.toByteArray(Charsets.UTF_8)
        // Byte mode header is 4 bits mode (0100) + 8 bits length = 12 bits -> takes 2 bytes overhead
        val spec = SPECS.firstOrNull { payload.size + 2 <= it.totalDataCodewords } ?: return null

        // Build bit buffer
        val bits = ArrayList<Int>(spec.totalDataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        // Mode indicator: Byte mode (0100)
        pushBits(0b0100, 4)
        // Character count indicator (8 bits for Versions 1..9)
        pushBits(payload.size, 8)
        for (b in payload) {
            pushBits(b.toInt() and 0xFF, 8)
        }

        val maxDataBits = spec.totalDataCodewords * 8
        // Terminator (up to 4 zero bits)
        val termLen = minOf(4, maxDataBits - bits.size)
        pushBits(0, termLen)
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)

        // Pad codewords 0xEC, 0x11 alternating
        val dataBytes = ByteArray(spec.totalDataCodewords)
        val writtenBytes = bits.size / 8
        for (i in 0 until writtenBytes) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or bits[i * 8 + b]
            }
            dataBytes[i] = v.toByte()
        }
        var padIdx = 0
        for (i in writtenBytes until spec.totalDataCodewords) {
            dataBytes[i] = if (padIdx % 2 == 0) 0xEC.toByte() else 0x11.toByte()
            padIdx++
        }

        // Split into blocks, compute RS ECC, and interleave
        val blockDataLen = spec.totalDataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            dataBytes.copyOfRange(b * blockDataLen, (b + 1) * blockDataLen)
        }
        val eccBlocks = Array(spec.numBlocks) { b ->
            rsComputeRemainder(dataBlocks[b], spec.eccPerBlock)
        }

        val finalCodewords = ArrayList<Byte>(spec.totalDataCodewords + spec.numBlocks * spec.eccPerBlock)
        for (i in 0 until blockDataLen) {
            for (b in 0 until spec.numBlocks) finalCodewords.add(dataBlocks[b][i])
        }
        for (i in 0 until spec.eccPerBlock) {
            for (b in 0 until spec.numBlocks) finalCodewords.add(eccBlocks[b][i])
        }

        val size = spec.size
        val isFunction = Array(size) { BooleanArray(size) }
        val baseModules = Array(size) { BooleanArray(size) }

        fun setFn(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                baseModules[r][c] = dark
                isFunction[r][c] = true
            }
        }

        // Draw Finder patterns + separators
        fun drawFinder(top: Int, left: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = top + dr
                    val c = left + dc
                    if (r !in 0 until size || c !in 0 until size) continue
                    val dark = dr in 0..6 && dc in 0..6 && (
                        dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4)
                    )
                    setFn(r, c, dark)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            setFn(6, i, i % 2 == 0)
            setFn(i, 6, i % 2 == 0)
        }

        // Alignment pattern (Versions 2..6 have single alignment center at (alignCoord, alignCoord))
        if (spec.alignCoord > 0) {
            val cr = spec.alignCoord
            val cc = spec.alignCoord
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val dark = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1
                    setFn(cr + dr, cc + dc, dark)
                }
            }
        }

        // Reserve Format Info areas + Dark module
        for (i in 0..8) {
            if (i != 6) {
                setFn(8, i, false)
                setFn(i, 8, false)
            }
        }
        for (i in size - 8 until size) {
            setFn(8, i, false)
            setFn(i, 8, false)
        }
        setFn(size - 8, 8, true) // Dark module (4*V + 9, 8)

        // Place data + ECC bits in standard upward/downward 2-column zig-zag
        var bitIdx = 0
        val totalBits = finalCodewords.size * 8
        var upward = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col-- // Skip vertical timing column
            for (i in 0 until size) {
                val r = if (upward) size - 1 - i else i
                for (dc in 0..1) {
                    val c = col - dc
                    if (!isFunction[r][c]) {
                        val bit = if (bitIdx < totalBits) {
                            ((finalCodewords[bitIdx / 8].toInt() and 0xFF) ushr (7 - (bitIdx % 8))) and 1
                        } else 0
                        baseModules[r][c] = (bit == 1)
                        bitIdx++
                    }
                }
            }
            upward = !upward
            col -= 2
        }

        // Try masks 0..7 and choose lowest penalty score
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestMatrix: Array<BooleanArray>? = null

        for (mask in 0..7) {
            val mat = Array(size) { r -> baseModules[r].copyOf() }
            for (r in 0 until size) {
                for (c in 0 until size) {
                    if (!isFunction[r][c] && shouldFlipMask(mask, r, c)) {
                        mat[r][c] = !mat[r][c]
                    }
                }
            }
            applyFormatBits(mat, size, eccLevelBits = 0b01, mask = mask)
            val penalty = computePenalty(mat, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestMatrix = mat
            }
        }
        return bestMatrix
    }

    private fun shouldFlipMask(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> ((r / 2) + (c / 3)) % 2 == 0
        5 -> ((r * c) % 2) + ((r * c) % 3) == 0
        6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
        7 -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
        else -> false
    }

    private fun computeFormatBits(eccLevelBits: Int, mask: Int): Int {
        val data = (eccLevelBits shl 3) or mask
        var rem = data shl 10
        for (i in 14 downTo 10) {
            if (((rem ushr i) and 1) != 0) {
                rem = rem xor (0x537 shl (i - 10))
            }
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun applyFormatBits(mat: Array<BooleanArray>, size: Int, eccLevelBits: Int, mask: Int) {
        val fmt = computeFormatBits(eccLevelBits, mask)
        // Top-left format strip
        for (i in 0..5) mat[8][i] = ((fmt ushr (14 - i)) and 1) == 1
        mat[8][7] = ((fmt ushr 8) and 1) == 1
        mat[8][8] = ((fmt ushr 7) and 1) == 1
        mat[7][8] = ((fmt ushr 6) and 1) == 1
        for (i in 0..5) mat[5 - i][8] = ((fmt ushr (5 - i)) and 1) == 1

        // Bottom-left & top-right format strips
        for (i in 0..6) mat[size - 1 - i][8] = ((fmt ushr (14 - i)) and 1) == 1
        for (i in 0..7) mat[8][size - 8 + i] = ((fmt ushr (7 - i)) and 1) == 1
    }

    private fun computePenalty(mat: Array<BooleanArray>, size: Int): Int {
        var score = 0
        // Rule 1: adjacent modules in row/col
        for (r in 0 until size) {
            var run = 1
            for (c in 1 until size) {
                if (mat[r][c] == mat[r][c - 1]) {
                    run++
                } else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        for (c in 0 until size) {
            var run = 1
            for (r in 1 until size) {
                if (mat[r][c] == mat[r - 1][c]) {
                    run++
                } else {
                    if (run >= 5) score += 3 + (run - 5)
                    run = 1
                }
            }
            if (run >= 5) score += 3 + (run - 5)
        }
        // Rule 2: 2x2 blocks
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val v = mat[r][c]
                if (mat[r][c + 1] == v && mat[r + 1][c] == v && mat[r + 1][c + 1] == v) {
                    score += 3
                }
            }
        }
        return score
    }

    /**
     * Renders an ISO/IEC 18004 QR Code with a 4-module white quiet zone into an Android [Bitmap].
     */
    fun generateBitmap(text: String, pixelSize: Int = 420): Bitmap? {
        val matrix = encodeMatrix(text) ?: return null
        val quiet = 4
        val modCount = matrix.size + quiet * 2
        val scale = maxOf(1, pixelSize / modCount)
        val imgSize = modCount * scale
        val pixels = IntArray(imgSize * imgSize) { Color.WHITE }

        for (r in matrix.indices) {
            for (c in matrix.indices) {
                if (matrix[r][c]) {
                    val y0 = (r + quiet) * scale
                    val x0 = (c + quiet) * scale
                    for (dy in 0 until scale) {
                        val rowOff = (y0 + dy) * imgSize + x0
                        for (dx in 0 until scale) {
                            pixels[rowOff + dx] = Color.BLACK
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, imgSize, imgSize, Bitmap.Config.ARGB_8888)
    }
}
