// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Pure-Kotlin/Java Zero-Dependency Self-Signed ECDSA P-256 X.509 v3 Certificate Generator
 * & SSLServerSocketFactory builder for PulseMesh v1.3.0 Hotspot HTTPS (`:8443`).
 *
 * Uses only standard Android/JDK `java.security` + `javax.net.ssl` APIs (100% FOSS / F-Droid safe).
 * Persists the generated PKCS8 key + DER certificate in `filesDir/pulsemesh_tls/` so browsers
 * only see one stable local certificate per host phone.
 */
object LocalSelfSignedTlsFactory {

    private const val DIR_NAME = "pulsemesh_tls"
    private const val KEY_FILE = "host_ec_p256.pk8"
    private const val CERT_FILE = "host_cert.der"

    fun createSslServerSocketFactory(context: Context): SSLServerSocketFactory? {
        return runCatching {
            val (privateKey, cert) = loadOrGenerateKeyAndCert(context)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("pulsemesh-host", privateKey, "pulsemesh".toCharArray(), arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(ks, "pulsemesh".toCharArray())
            }
            val sslCtx = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, SecureRandom())
            }
            sslCtx.serverSocketFactory
        }.getOrNull()
    }

    private fun loadOrGenerateKeyAndCert(context: Context): Pair<PrivateKey, X509Certificate> {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)

        if (keyFile.exists() && certFile.exists() && keyFile.length() > 32 && certFile.length() > 100) {
            runCatching {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                cert.checkValidity()
                return Pair(priv, cert)
            }
        }

        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }
        val kp = kpg.generateKeyPair()
        val certDer = buildSelfSignedEcX509Der(kp)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        runCatching {
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(certDer)
        }
        return Pair(kp.private, cert)
    }

    internal fun buildSelfSignedEcX509Der(keyPair: KeyPair): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years
        val serial = BigInteger(63, SecureRandom()).abs().max(BigInteger.ONE)

        // OID 1.2.840.10045.4.3.2 = ecdsa-with-SHA256
        val sigAlgId = derSeq(derOid(intArrayOf(1, 2, 840, 10045, 4, 3, 2)))

        // CN=PulseMesh Local Portal, O=PulseMesh Off-Grid
        val dn = derSeq(
            derSet(derSeq(derOid(intArrayOf(2, 5, 4, 3)), derUtf8("PulseMesh Local Portal"))),
            derSet(derSeq(derOid(intArrayOf(2, 5, 4, 10)), derUtf8("PulseMesh")))
        )

        val validity = derSeq(derUtcTime(notBefore), derUtcTime(notAfter))
        val spki = keyPair.public.encoded // Already DER SubjectPublicKeyInfo

        // v3 Extensions: BasicConstraints(CA:TRUE), KeyUsage, SAN(DNS:pulsemesh.local, IP:127.0.0.1, 192.168.43.1, 192.168.49.1)
        val basicConstraints = derSeq(
            derOid(intArrayOf(2, 5, 29, 19)),
            derOctetString(derSeq(byteArrayOf(0x01, 0x01, 0xFF.toByte())))
        )
        val sanValue = derSeq(
            derContextPrimitive(2, "pulsemesh.local".toByteArray(Charsets.US_ASCII)),
            derContextPrimitive(7, byteArrayOf(127, 0, 0, 1)),
            derContextPrimitive(7, byteArrayOf(192.toByte(), 168.toByte(), 43, 1)),
            derContextPrimitive(7, byteArrayOf(192.toByte(), 168.toByte(), 49, 1))
        )
        val sanExt = derSeq(
            derOid(intArrayOf(2, 5, 29, 17)),
            derOctetString(sanValue)
        )
        val extensions = derContextConstructed(3, derSeq(basicConstraints, sanExt))

        val tbs = derSeq(
            derContextConstructed(0, derInt(BigInteger.valueOf(2))), // version v3 (2)
            derInt(serial),
            sigAlgId,
            dn,
            validity,
            dn,
            spki,
            extensions
        )

        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }
        val sigBytes = signer.sign()
        return derSeq(tbs, sigAlgId, derBitString(sigBytes))
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len < 256 -> {
                out.write(0x81)
                out.write(len)
            }
            len < 65536 -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun derSeq(vararg items: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        for (i in items) buf.write(i)
        return derTag(0x30, buf.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        for (i in items) buf.write(i)
        return derTag(0x31, buf.toByteArray())
    }

    private fun derInt(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

    private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)

    private fun derBitString(bytes: ByteArray): ByteArray {
        val prefixed = ByteArray(bytes.size + 1)
        prefixed[0] = 0
        System.arraycopy(bytes, 0, prefixed, 1, bytes.size)
        return derTag(0x03, prefixed)
    }

    private fun derUtf8(text: String): ByteArray = derTag(0x0C, text.toByteArray(Charsets.UTF_8))

    private fun derContextPrimitive(tagNum: Int, bytes: ByteArray): ByteArray = derTag(0x80 or tagNum, bytes)

    private fun derContextConstructed(tagNum: Int, bytes: ByteArray): ByteArray = derTag(0xA0 or tagNum, bytes)

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun derOid(arcs: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val stack = IntArray(8)
            var count = 0
            stack[count++] = v and 0x7F
            v = v ushr 7
            while (v > 0) {
                stack[count++] = (v and 0x7F) or 0x80
                v = v ushr 7
            }
            for (k in count - 1 downTo 0) out.write(stack[k])
        }
        return derTag(0x06, out.toByteArray())
    }
}
