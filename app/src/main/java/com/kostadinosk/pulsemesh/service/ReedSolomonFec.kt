// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

/**
 * Systematic Reed-Solomon Forward Error Correction over GF(2^8).
 *
 * Uses primitive polynomial `0x11D` (`x^8 + x^4 + x^3 + x^2 + 1`), generator `alpha = 2`,
 * and first consecutive root `alpha^0 = 1`. Designed to match the PulseMesh Web/iPhone PWA
 * Acoustic Modem 2.0 decoder byte-for-byte.
 *
 * With [DEFAULT_ECC_BYTES] = 8, each frame can detect up to 8 corrupted bytes and
 * mathematically repair up to `t = 4` arbitrary byte errors anywhere in the codeword.
 */
object ReedSolomonFec {

    const val DEFAULT_ECC_BYTES = 8
    private const val PRIMITIVE_POLY = 0x11D

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) {
                x = x xor PRIMITIVE_POLY
            }
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    data class DecodeResult(
        val data: ByteArray,
        val correctedErrors: Int
    )

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun gfDiv(a: Int, b: Int): Int {
        require(b != 0) { "Division by zero in GF(2^8)" }
        if (a == 0) return 0
        return GF_EXP[(GF_LOG[a] + 255 - GF_LOG[b]) % 255]
    }

    private fun gfEvalHighestFirst(poly: ByteArray, x: Int): Int {
        var y = 0
        for (b in poly) {
            y = gfMul(y, x) xor (b.toInt() and 0xFF)
        }
        return y
    }

    private fun polyMul(p: IntArray, q: IntArray): IntArray {
        val out = IntArray(p.size + q.size - 1)
        for (i in p.indices) {
            val pi = p[i]
            if (pi == 0) continue
            for (j in q.indices) {
                val qj = q[j]
                if (qj != 0) {
                    out[i + j] = out[i + j] xor gfMul(pi, qj)
                }
            }
        }
        return out
    }

    /**
     * Highest-degree-first generator polynomial `prod_{i=0}^{eccBytes-1} (x - alpha^i)`.
     */
    private fun generatorPoly(eccBytes: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until eccBytes) {
            g = polyMul(g, intArrayOf(1, GF_EXP[i]))
        }
        return g
    }

    /**
     * Appends [eccBytes] systematic Reed-Solomon parity bytes to [data].
     */
    fun encode(data: ByteArray, eccBytes: Int = DEFAULT_ECC_BYTES): ByteArray {
        require(data.isNotEmpty()) { "Reed-Solomon payload cannot be empty" }
        require(eccBytes in 2..32 && (eccBytes and 1) == 0) { "eccBytes must be an even integer in 2..32" }
        require(data.size + eccBytes <= 255) { "RS(255, k) codeword cannot exceed 255 bytes" }

        val gen = generatorPoly(eccBytes)
        val work = IntArray(data.size + eccBytes)
        for (i in data.indices) {
            work[i] = data[i].toInt() and 0xFF
        }

        for (i in data.indices) {
            val coef = work[i]
            if (coef != 0) {
                for (j in 1 until gen.size) {
                    work[i + j] = work[i + j] xor gfMul(gen[j], coef)
                }
            }
        }

        val out = ByteArray(data.size + eccBytes)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in 0 until eccBytes) {
            out[data.size + i] = work[data.size + i].toByte()
        }
        return out
    }

    /**
     * Verifies and repairs up to `eccBytes / 2` corrupted bytes in [codeword].
     * Returns `null` if errors exceed repair capacity or syndromes fail post-correction check.
     */
    fun decodeWithStats(codeword: ByteArray, eccBytes: Int = DEFAULT_ECC_BYTES): DecodeResult? {
        if (eccBytes !in 2..32 || (eccBytes and 1) != 0) return null
        if (codeword.size <= eccBytes || codeword.size > 255) return null

        val dataLen = codeword.size - eccBytes
        val syndromes = IntArray(eccBytes)
        var anyNonZero = false
        for (i in 0 until eccBytes) {
            val s = gfEvalHighestFirst(codeword, GF_EXP[i])
            syndromes[i] = s
            if (s != 0) anyNonZero = true
        }
        if (!anyNonZero) {
            return DecodeResult(data = codeword.copyOf(dataLen), correctedErrors = 0)
        }

        // Berlekamp-Massey algorithm (lowest degree first: Lambda[0] = 1)
        var c = intArrayOf(1)
        var b = intArrayOf(1)
        var l = 0
        var m = 1
        var bScalar = 1

        for (n in 0 until eccBytes) {
            var d = syndromes[n]
            for (i in 1..l) {
                if (i < c.size) {
                    d = d xor gfMul(c[i], syndromes[n - i])
                }
            }
            if (d == 0) {
                m += 1
            } else {
                val t = c.copyOf()
                val factor = gfDiv(d, bScalar)
                val next = IntArray(maxOf(c.size, b.size + m))
                System.arraycopy(c, 0, next, 0, c.size)
                for (i in b.indices) {
                    next[i + m] = next[i + m] xor gfMul(factor, b[i])
                }
                c = next
                if (2 * l <= n) {
                    l = n + 1 - l
                    b = t
                    bScalar = d
                    m = 1
                } else {
                    m += 1
                }
            }
        }

        if (l == 0 || l > eccBytes / 2) return null
        val actualDeg = (c.size - 1 downTo 0).firstOrNull { c[it] != 0 } ?: return null
        if (actualDeg != l) return null

        // Chien search for roots X_k^{-1} corresponding to codeword array indices
        val errIndices = IntArray(l)
        val errX = IntArray(l)
        val errXInv = IntArray(l)
        var found = 0

        for (idx in codeword.indices) {
            val power = codeword.size - 1 - idx
            val xVal = GF_EXP[power % 255]
            val xInv = GF_EXP[(255 - (power % 255)) % 255]
            var eval = 0
            var xPow = 1
            for (coef in c) {
                if (coef != 0) {
                    eval = eval xor gfMul(coef, xPow)
                }
                xPow = gfMul(xPow, xInv)
            }
            if (eval == 0) {
                if (found >= l) return null
                errIndices[found] = idx
                errX[found] = xVal
                errXInv[found] = xInv
                found++
            }
        }
        if (found != l) return null

        // Error evaluator Omega(x) = (S(x) * Lambda(x)) mod x^eccBytes (lowest degree first)
        val omega = IntArray(eccBytes)
        for (i in 0 until eccBytes) {
            var acc = 0
            for (j in 0..minOf(i, actualDeg)) {
                acc = acc xor gfMul(syndromes[i - j], c[j])
            }
            omega[i] = acc
        }

        // Formal derivative Lambda'(x): odd terms shifted down by 1 in GF(2^8)
        val corrected = codeword.copyOf()
        for (k in 0 until l) {
            val xInv = errXInv[k]

            var omegaEval = 0
            var xPow = 1
            for (i in 0 until eccBytes) {
                if (omega[i] != 0) {
                    omegaEval = omegaEval xor gfMul(omega[i], xPow)
                }
                xPow = gfMul(xPow, xInv)
            }

            var derivEval = 0
            var derivPow = 1
            var i = 1
            while (i <= actualDeg) {
                if (c[i] != 0) {
                    derivEval = derivEval xor gfMul(c[i], derivPow)
                }
                derivPow = gfMul(derivPow, gfMul(xInv, xInv))
                i += 2
            }
            if (derivEval == 0) return null

            val magnitude = gfMul(errX[k], gfDiv(omegaEval, derivEval))
            val pos = errIndices[k]
            corrected[pos] = ((corrected[pos].toInt() and 0xFF) xor magnitude).toByte()
        }

        // Strict post-correction syndrome verification
        for (i in 0 until eccBytes) {
            if (gfEvalHighestFirst(corrected, GF_EXP[i]) != 0) {
                return null
            }
        }

        return DecodeResult(data = corrected.copyOf(dataLen), correctedErrors = l)
    }

    fun decode(codeword: ByteArray, eccBytes: Int = DEFAULT_ECC_BYTES): ByteArray? =
        decodeWithStats(codeword, eccBytes)?.data
}
