// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
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
 * Pure-Kotlin/Java Zero-Dependency Self-Signed ECDSA P-256 (with RSA-2048 fallback)
 * X.509 v3 Certificate Generator & SSLServerSocketFactory builder for PulseMesh Hotspot HTTPS (`:8443`).
 *
 * Uses only standard Android/JDK `java.security` + `javax.net.ssl` APIs (100% FOSS / F-Droid safe).
 * Generates a browser-compliant 365-day serverAuth leaf certificate with dynamic IP SANs matching
 * the host device's live SoftAP / Wi-Fi Direct / LAN IPv4 addresses.
 */
object LocalSelfSignedTlsFactory {

    private const val DIR_NAME = "pulsemesh_tls"
    private const val ROOT_CA_KEY_FILE = "root_ca_key.pk8"
    private const val ROOT_CA_CERT_FILE = "root_ca_cert.der"
    private const val KEY_FILE = "host_ec_p256.pk8"
    private const val CERT_FILE = "host_cert.der"
    private const val ALG_FILE = "host_alg.txt"
    private const val SANS_FILE = "host_sans.txt"
    private const val ROOT_CA_VALIDITY_DAYS = 3650L
    private const val CERT_VALIDITY_DAYS = 365L
    private const val MAX_BROWSER_CERT_DAYS = 397L

    private val DEFAULT_LOCAL_IPS = listOf(
        "127.0.0.1",
        "192.168.43.1",
        "192.168.49.1",
        "192.168.137.1"
    )

    data class TlsPkiMaterial(
        val leafPrivateKey: PrivateKey,
        val leafCert: X509Certificate,
        val rootCaCert: X509Certificate
    )

    fun createSslServerSocketFactory(
        context: Context,
        extraIps: List<String> = emptyList()
    ): Pair<SSLServerSocketFactory?, String> {
        val mergedIps = normalizeSanIps(extraIps)
        val errors = mutableListOf<String>()

        // 1. Try modern 2-Tier ECDSA P-256 Root CA + Server Leaf PKI (TLS 1.3 & iOS/Android Root Trust compatible)
        try {
            val pki = loadOrGenerateTwoTierPki(context, mergedIps, useRsa = false)
            val factory = buildSocketFactoryFromKeyStore(pki.leafPrivateKey, arrayOf(pki.leafCert, pki.rootCaCert))
            return Pair(factory, "")
        } catch (e: Exception) {
            errors += "EC: ${e.javaClass.simpleName}: ${e.message}"
        }

        // 2. Fallback to RSA-2048 2-Tier PKI if an OEM KeyStore / BKS provider rejects EC keys
        try {
            val pki = loadOrGenerateTwoTierPki(context, mergedIps, useRsa = true)
            val factory = buildSocketFactoryFromKeyStore(pki.leafPrivateKey, arrayOf(pki.leafCert, pki.rootCaCert))
            return Pair(factory, "")
        } catch (e: Exception) {
            errors += "RSA: ${e.javaClass.simpleName}: ${e.message}"
        }

        return Pair(null, errors.joinToString(" | "))
    }

    /**
     * Returns the persistent Root CA DER certificate (`BasicConstraints: CA=TRUE`) so Android,
     * iOS, and desktop browsers can install it as a trusted Root Certificate Authority and
     * eliminate all browser SSL warnings on `https://<hotspot-ip>:8443`.
     */
    fun getRootCaCertificateDer(context: Context): ByteArray? {
        val dir = File(context.filesDir, DIR_NAME)
        val caFile = File(dir, ROOT_CA_CERT_FILE)
        if (!caFile.exists() || caFile.length() <= 100) {
            runCatching { loadOrGenerateTwoTierPki(context, DEFAULT_LOCAL_IPS, useRsa = false) }
        }
        return runCatching {
            if (caFile.exists() && caFile.length() > 100) caFile.readBytes() else null
        }.getOrNull()
    }

    fun getLeafCertificateDer(context: Context): ByteArray? {
        val certFile = File(File(context.filesDir, DIR_NAME), CERT_FILE)
        return runCatching {
            if (certFile.exists() && certFile.length() > 100) certFile.readBytes() else null
        }.getOrNull()
    }

    fun getCurrentCertificateDer(context: Context): ByteArray? {
        return getLeafCertificateDer(context) ?: getRootCaCertificateDer(context)
    }

    fun getRootCaCertificatePem(context: Context): String? {
        val der = getRootCaCertificateDer(context) ?: return null
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII)).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    /**
     * Builds an Apple iOS/iPadOS Configuration Profile (`.mobileconfig`) embedding the
     * PulseMesh Local Root CA (`com.apple.security.root`) for 1-tap iPhone Safari installation.
     */
    fun buildIosMobileConfigXml(context: Context, hostIp: String = "192.168.43.1"): ByteArray? {
        val rootDer = getRootCaCertificateDer(context) ?: return null
        val b64Cert = java.util.Base64.getEncoder().encodeToString(rootDer)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>PayloadContent</key>
    <array>
        <dict>
            <key>PayloadCertificateFileName</key>
            <string>PulseMesh-Root-CA.cer</string>
            <key>PayloadContent</key>
            <data>$b64Cert</data>
            <key>PayloadDescription</key>
            <string>Installs the PulseMesh Local Root Certificate Authority for zero-warning offline HTTPS (:8443) voice calling and GPS.</string>
            <key>PayloadDisplayName</key>
            <string>PulseMesh Local Root CA</string>
            <key>PayloadIdentifier</key>
            <string>org.pulsemesh.local.rootca.cert</string>
            <key>PayloadType</key>
            <string>com.apple.security.root</string>
            <key>PayloadUUID</key>
            <string>7A4F9E21-5B1D-4C8E-9A32-112233445566</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
        </dict>
    </array>
    <key>PayloadDescription</key>
    <string>Trusts the local PulseMesh Hotspot SSL Root CA ($hostIp:8443) so Safari and Chrome open with a verified padlock and full microphone/GPS permissions.</string>
    <key>PayloadDisplayName</key>
    <string>PulseMesh Offline SSL Root CA</string>
    <key>PayloadIdentifier</key>
    <string>org.pulsemesh.local.rootca.profile</string>
    <key>PayloadOrganization</key>
    <string>PulseMesh Tactical Mesh</string>
    <key>PayloadRemovalDisallowed</key>
    <false/>
    <key>PayloadType</key>
    <string>Configuration</string>
    <key>PayloadUUID</key>
    <string>9B8E7C12-4F3A-4D9E-8C11-665544332211</string>
    <key>PayloadVersion</key>
    <integer>1</integer>
</dict>
</plist>
"""
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun buildSocketFactoryFromKeyStore(
        privateKey: PrivateKey,
        certChain: Array<X509Certificate>
    ): SSLServerSocketFactory {
        val password = "pulsemesh".toCharArray()
        val storeTypes = listOf("PKCS12", KeyStore.getDefaultType(), "BKS").distinct()
        var lastErr: Exception? = null
        for (ksType in storeTypes) {
            try {
                val ks = KeyStore.getInstance(ksType).apply {
                    load(null, null)
                    setKeyEntry("pulsemesh-host", privateKey, password, certChain)
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(ks, password)
                }
                val sslCtx = SSLContext.getInstance("TLS").apply {
                    init(kmf.keyManagers, null, SecureRandom())
                }
                return sslCtx.serverSocketFactory
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("No compatible KeyStore provider found for TLS")
    }

    private fun normalizeSanIps(extraIps: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(DEFAULT_LOCAL_IPS)
        for (raw in extraIps) {
            val clean = raw.trim()
            if (clean.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                val parsed = runCatching { InetAddress.getByName(clean) }.getOrNull()
                if (parsed is Inet4Address) {
                    out.add(clean)
                }
            }
        }
        return out.sorted()
    }

    private fun loadOrGenerateTwoTierPki(
        context: Context,
        sanIps: List<String>,
        useRsa: Boolean
    ): TlsPkiMaterial {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val rootKeyFile = File(dir, ROOT_CA_KEY_FILE)
        val rootCertFile = File(dir, ROOT_CA_CERT_FILE)
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        val algFile = File(dir, ALG_FILE)
        val sansFile = File(dir, SANS_FILE)
        val wantAlg = if (useRsa) "RSA" else "EC"
        val wantSans = sanIps.joinToString(",")
        val cf = CertificateFactory.getInstance("X.509")

        // 1. Load or generate persistent 10-year PulseMesh Local Root CA (`CA:TRUE`)
        var rootPriv: PrivateKey? = null
        var rootCert: X509Certificate? = null
        if (rootKeyFile.exists() && rootCertFile.exists() && rootKeyFile.length() > 32 && rootCertFile.length() > 100) {
            runCatching {
                val savedAlg = if (algFile.exists()) algFile.readText().trim() else wantAlg
                if (savedAlg == wantAlg) {
                    val kf = KeyFactory.getInstance(savedAlg)
                    val loadedPriv = kf.generatePrivate(PKCS8EncodedKeySpec(rootKeyFile.readBytes()))
                    val loadedCert = cf.generateCertificate(ByteArrayInputStream(rootCertFile.readBytes())) as X509Certificate
                    loadedCert.checkValidity()
                    if (loadedCert.basicConstraints >= 0) {
                        rootPriv = loadedPriv
                        rootCert = loadedCert
                    }
                }
            }
        }

        if (rootPriv == null || rootCert == null) {
            val rootKp = generateKeyPair(useRsa)
            val rootDer = buildRootCaX509Der(rootKp, useRsa)
            val parsedRoot = cf.generateCertificate(ByteArrayInputStream(rootDer)) as X509Certificate
            rootPriv = rootKp.private
            rootCert = parsedRoot
            runCatching {
                rootKeyFile.writeBytes(rootKp.private.encoded)
                rootCertFile.writeBytes(rootDer)
                algFile.writeText(wantAlg)
            }
        }

        val activeRootPriv = rootPriv!!
        val activeRootCert = rootCert!!

        // 2. Load or generate 365-day Server Leaf Certificate signed by Root CA
        if (keyFile.exists() && certFile.exists() && keyFile.length() > 32 && certFile.length() > 100) {
            runCatching {
                val savedAlg = if (algFile.exists()) algFile.readText().trim() else wantAlg
                val savedSans = if (sansFile.exists()) sansFile.readText().trim() else ""
                val kf = KeyFactory.getInstance(savedAlg)
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                cert.checkValidity()
                cert.verify(activeRootCert.publicKey)
                val now = System.currentTimeMillis()
                val remainingMs = cert.notAfter.time - now
                val maxAllowedMs = MAX_BROWSER_CERT_DAYS * 86_400_000L
                val minRefreshMs = 7L * 86_400_000L
                if (savedAlg == wantAlg &&
                    savedSans == wantSans &&
                    remainingMs in minRefreshMs..maxAllowedMs
                ) {
                    return TlsPkiMaterial(priv, cert, activeRootCert)
                }
            }
        }

        val leafKp = generateKeyPair(useRsa)
        val leafDer = buildSignedLeafX509Der(
            leafKeyPair = leafKp,
            issuerPrivateKey = activeRootPriv,
            sanIps = sanIps,
            useRsa = useRsa
        )
        val leafCert = cf.generateCertificate(ByteArrayInputStream(leafDer)) as X509Certificate

        runCatching {
            keyFile.writeBytes(leafKp.private.encoded)
            certFile.writeBytes(leafDer)
            algFile.writeText(wantAlg)
            sansFile.writeText(wantSans)
        }
        return TlsPkiMaterial(leafKp.private, leafCert, activeRootCert)
    }

    private fun generateKeyPair(useRsa: Boolean): KeyPair {
        return if (useRsa) {
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
        } else {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
        }
    }

    internal fun buildTwoTierPkiDer(
        rootKeyPair: KeyPair,
        leafKeyPair: KeyPair,
        extraIps: List<String> = emptyList(),
        useRsa: Boolean = false
    ): Pair<ByteArray, ByteArray> {
        val sanIps = normalizeSanIps(extraIps)
        val rootDer = buildRootCaX509Der(rootKeyPair, useRsa = useRsa)
        val leafDer = buildSignedLeafX509Der(leafKeyPair, rootKeyPair.private, sanIps, useRsa = useRsa)
        return Pair(rootDer, leafDer)
    }

    internal fun buildSelfSignedEcX509Der(
        keyPair: KeyPair,
        extraIps: List<String> = emptyList()
    ): ByteArray = buildSignedLeafX509Der(keyPair, keyPair.private, normalizeSanIps(extraIps), useRsa = false)

    internal fun buildRootCaX509Der(
        rootKeyPair: KeyPair,
        useRsa: Boolean = false
    ): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + ROOT_CA_VALIDITY_DAYS * 24L * 60 * 60 * 1000)
        val serial = BigInteger(63, SecureRandom()).abs().max(BigInteger.ONE)

        val sigAlgId = if (useRsa) {
            derSeq(derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)), derNull())
        } else {
            derSeq(derOid(intArrayOf(1, 2, 840, 10045, 4, 3, 2)))
        }

        val rootDn = buildRootCaDn()
        val validity = derSeq(derUtcTime(notBefore), derUtcTime(notAfter))
        val spki = rootKeyPair.public.encoded

        // 1. BasicConstraints: CA=TRUE, pathLenConstraint=0 (critical)
        val basicConstraints = derSeq(
            derOid(intArrayOf(2, 5, 29, 19)),
            derBooleanTrue(),
            derOctetString(derSeq(derBooleanTrue(), derInt(BigInteger.ZERO)))
        )
        // 2. KeyUsage: keyCertSign (bit 5) | cRLSign (bit 6) = 0x06, unusedBits=1 (critical)
        val keyUsage = derSeq(
            derOid(intArrayOf(2, 5, 29, 15)),
            derBooleanTrue(),
            derOctetString(byteArrayOf(0x03, 0x02, 0x01, 0x06))
        )
        // 3. SubjectKeyIdentifier (2.5.29.14)
        val skiHash = java.security.MessageDigest.getInstance("SHA-1").digest(spki)
        val subjectKeyId = derSeq(
            derOid(intArrayOf(2, 5, 29, 14)),
            derOctetString(derOctetString(skiHash))
        )
        val extensions = derContextConstructed(3, derSeq(basicConstraints, keyUsage, subjectKeyId))

        val tbs = derSeq(
            derContextConstructed(0, derInt(BigInteger.valueOf(2))),
            derInt(serial),
            sigAlgId,
            rootDn,
            validity,
            rootDn,
            spki,
            extensions
        )

        val signer = Signature.getInstance(if (useRsa) "SHA256withRSA" else "SHA256withECDSA").apply {
            initSign(rootKeyPair.private)
            update(tbs)
        }
        return derSeq(tbs, sigAlgId, derBitString(signer.sign()))
    }

    private fun buildRootCaDn(): ByteArray = derSeq(
        derSet(derSeq(derOid(intArrayOf(2, 5, 4, 3)), derUtf8("PulseMesh Local Root CA"))),
        derSet(derSeq(derOid(intArrayOf(2, 5, 4, 10)), derUtf8("PulseMesh")))
    )

    private fun buildLeafDn(): ByteArray = derSeq(
        derSet(derSeq(derOid(intArrayOf(2, 5, 4, 3)), derUtf8("PulseMesh Local Portal"))),
        derSet(derSeq(derOid(intArrayOf(2, 5, 4, 10)), derUtf8("PulseMesh")))
    )

    private fun buildSignedLeafX509Der(
        leafKeyPair: KeyPair,
        issuerPrivateKey: PrivateKey,
        sanIps: List<String>,
        useRsa: Boolean
    ): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + CERT_VALIDITY_DAYS * 24L * 60 * 60 * 1000)
        val serial = BigInteger(63, SecureRandom()).abs().max(BigInteger.ONE)

        val sigAlgId = if (useRsa) {
            // OID 1.2.840.113549.1.1.11 = sha256WithRSAEncryption + NULL
            derSeq(derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)), derNull())
        } else {
            // OID 1.2.840.10045.4.3.2 = ecdsa-with-SHA256
            derSeq(derOid(intArrayOf(1, 2, 840, 10045, 4, 3, 2)))
        }

        val issuerDn = buildRootCaDn()
        val subjectDn = buildLeafDn()

        val validity = derSeq(derUtcTime(notBefore), derUtcTime(notAfter))
        val spki = leafKeyPair.public.encoded

        // v3 Extensions compliant with Chrome, Android WebView & iOS Safari:
        // 1. BasicConstraints: CA=FALSE (critical)
        val basicConstraints = derSeq(
            derOid(intArrayOf(2, 5, 29, 19)),
            derBooleanTrue(),
            derOctetString(derSeq())
        )
        // 2. KeyUsage: digitalSignature (bit 0) + keyEncipherment (bit 2) = 0xA0, unusedBits=5 (critical)
        val keyUsage = derSeq(
            derOid(intArrayOf(2, 5, 29, 15)),
            derBooleanTrue(),
            derOctetString(byteArrayOf(0x03, 0x02, 0x05, 0xA0.toByte()))
        )
        // 3. ExtendedKeyUsage: id-kp-serverAuth (1.3.6.1.5.5.7.3.1)
        val extKeyUsage = derSeq(
            derOid(intArrayOf(2, 5, 29, 37)),
            derOctetString(derSeq(derOid(intArrayOf(1, 3, 6, 1, 5, 5, 7, 3, 1))))
        )
        // 4. SubjectAlternativeName: DNS + all live IPv4 addresses
        val sanItems = mutableListOf<ByteArray>()
        sanItems += derContextPrimitive(2, "pulsemesh.local".toByteArray(Charsets.US_ASCII))
        sanItems += derContextPrimitive(2, "localhost".toByteArray(Charsets.US_ASCII))
        for (ip in sanIps) {
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
            if (addr is Inet4Address) {
                sanItems += derContextPrimitive(7, addr.address)
            }
        }
        val sanExt = derSeq(
            derOid(intArrayOf(2, 5, 29, 17)),
            derOctetString(derSeq(*sanItems.toTypedArray()))
        )
        val extensions = derContextConstructed(3, derSeq(basicConstraints, keyUsage, extKeyUsage, sanExt))

        val tbs = derSeq(
            derContextConstructed(0, derInt(BigInteger.valueOf(2))), // version v3 (2)
            derInt(serial),
            sigAlgId,
            issuerDn,
            validity,
            subjectDn,
            spki,
            extensions
        )

        val signer = Signature.getInstance(if (useRsa) "SHA256withRSA" else "SHA256withECDSA").apply {
            initSign(issuerPrivateKey)
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

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derBooleanTrue(): ByteArray = byteArrayOf(0x01, 0x01, 0xFF.toByte())

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
