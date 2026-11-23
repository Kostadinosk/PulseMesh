// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class ApkInstallStage {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    AWAITING_PERMISSION,
    INSTALLING,
    INSTALLED,
    FAILED
}

data class ApkUpdateInstallState(
    val stage: ApkInstallStage = ApkInstallStage.IDLE,
    val progressPercent: Int = 0,
    val message: String = "",
    val verifiedSha256: String = ""
)

/**
 * Fail-closed SHA-256 + signing-certificate verified in-app APK updater using Android's
 * native [PackageInstaller] session API (`REQUEST_INSTALL_PACKAGES`).
 */
object VerifiedApkUpdater {

    private const val ACTION_INSTALL_STATUS = "com.kostadinosk.pulsemesh.ACTION_APK_INSTALL_STATUS"
    private val HEX_64_REGEX = Regex("\\b([0-9a-fA-F]{64})\\b")

    private val _status = MutableStateFlow(ApkUpdateInstallState())
    val status: StateFlow<ApkUpdateInstallState> = _status.asStateFlow()

    internal fun updateStatusFromReceiver(state: ApkUpdateInstallState) {
        _status.value = state
    }

    fun isAllowedUpdateUrl(url: String): Boolean {
        val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.protocol.lowercase()
        val host = parsed.host.lowercase()
        if (scheme == "https") return host.isNotBlank()
        if (scheme == "http") {
            return host == "localhost" ||
                host == "127.0.0.1" ||
                host.startsWith("192.168.") ||
                host.startsWith("10.") ||
                isPrivate172(host)
        }
        return false
    }

    private fun isPrivate172(host: String): Boolean {
        if (!host.startsWith("172.")) return false
        val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
        return secondOctet in 16..31
    }

    fun computeFileSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        FileInputStream(file).use { input ->
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun constantTimeHexEquals(a: String, b: String): Boolean {
        val normA = a.trim().lowercase()
        val normB = b.trim().lowercase()
        if (normA.length != 64 || normB.length != 64) return false
        var diff = 0
        for (i in 0 until 64) {
            diff = diff or (normA[i].code xor normB[i].code)
        }
        return diff == 0
    }

    fun parseExpectedSha256ForAsset(
        checksumText: String,
        assetFileName: String,
        requireFilenameMatch: Boolean = false
    ): String? {
        val cleanText = checksumText.trim()
        if (cleanText.isEmpty()) return null
        val cleanAsset = assetFileName.trim().lowercase()

        val lines = cleanText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (cleanAsset.isNotEmpty()) {
            val matched = lines.firstOrNull { line ->
                line.lowercase().contains(cleanAsset) && HEX_64_REGEX.containsMatchIn(line)
            }
            if (matched != null) {
                return HEX_64_REGEX.find(matched)?.groupValues?.getOrNull(1)?.lowercase()
            }
        }

        if (requireFilenameMatch) return null

        // Single-checksum `.sha256` file (`<64hex>` or `<64hex>  file.apk`)
        if (lines.size == 1) {
            return HEX_64_REGEX.find(lines[0])?.groupValues?.getOrNull(1)?.lowercase()
        }

        // Fall back only when asset name is blank and a lone digest exists
        if (cleanAsset.isEmpty()) {
            for (line in lines) {
                HEX_64_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { return it.lowercase() }
            }
        }
        return null
    }

    fun verifyApkSha256(apkFile: File, expectedSha256Hex: String): Boolean {
        if (!apkFile.isFile || expectedSha256Hex.trim().length != 64) return false
        val actual = runCatching { computeFileSha256Hex(apkFile) }.getOrNull() ?: return false
        return constantTimeHexEquals(actual, expectedSha256Hex)
    }

    @Suppress("DEPRECATION")
    private fun extractPackageSignatures(pkgInfo: android.content.pm.PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        val rawSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pkgInfo.signingInfo
            if (info != null) {
                val combined = mutableListOf<android.content.pm.Signature>()
                info.apkContentsSigners?.let { combined.addAll(it) }
                info.signingCertificateHistory?.let { combined.addAll(it) }
                combined.toTypedArray()
            } else {
                emptyArray()
            }
        } else {
            pkgInfo.signatures ?: emptyArray()
        }
        return rawSigs.map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    fun extractInstalledSigningCertDigests(pm: PackageManager, packageName: String): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
        return extractPackageSignatures(info)
    }

    @Suppress("DEPRECATION")
    fun extractArchiveSigningCertDigests(pm: PackageManager, apkFilePath: String): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFilePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(apkFilePath, flags)
            }
        }.getOrNull()
        return extractPackageSignatures(info)
    }

    fun verifySigningCertMatch(installedDigests: Set<String>, candidateDigests: Set<String>): Boolean {
        if (installedDigests.isEmpty() || candidateDigests.isEmpty()) return false
        return installedDigests.any { it in candidateDigests }
    }

    private fun openSafeConnection(initialUrl: String): HttpURLConnection {
        var current = initialUrl.trim()
        for (hop in 0 until 5) {
            require(isAllowedUpdateUrl(current)) { "Unsafe update URL rejected: $current" }
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "PulseMesh-VerifiedUpdater")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location").orEmpty().trim()
                runCatching { conn.disconnect() }
                require(loc.isNotBlank()) { "Redirect missing Location header" }
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code !in 200..299) {
                runCatching { conn.disconnect() }
                throw IllegalStateException("HTTP $code downloading $current")
            }
            return conn
        }
        throw IllegalStateException("Too many redirects downloading $initialUrl")
    }

    private fun downloadText(url: String): String {
        val conn = openSafeConnection(url)
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        dest: File,
        onPercent: (Int) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        val conn = openSafeConnection(url)
        try {
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            val buf = ByteArray(16 * 1024)
            var downloaded = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        downloaded += r
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onPercent(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    suspend fun downloadVerifyAndInstall(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (ApkUpdateInstallState) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        fun emit(state: ApkUpdateInstallState) {
            _status.value = state
            onProgress(state)
        }

        val apkUrl = update.apkDownloadUrl.trim()
        if (!isAllowedUpdateUrl(apkUrl) || !apkUrl.lowercase().contains(".apk")) {
            val fail = ApkUpdateInstallState(
                stage = ApkInstallStage.FAILED,
                message = "No direct APK asset URL in release; open GitHub Releases page manually."
            )
            emit(fail)
            return@withContext Result.failure(IllegalArgumentException(fail.message))
        }

        val safeFileName = update.apkFileName
            .ifBlank { apkUrl.substringAfterLast('/').substringBefore('?') }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "pulsemesh-update.apk" }
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, safeFileName)

        return@withContext runCatching {
            emit(ApkUpdateInstallState(ApkInstallStage.DOWNLOADING, 0, "Downloading $safeFileName..."))
            downloadFileWithProgress(apkUrl, apkFile) { pct ->
                emit(ApkUpdateInstallState(ApkInstallStage.DOWNLOADING, pct, "Downloading $safeFileName ($pct%)..."))
            }

            emit(ApkUpdateInstallState(ApkInstallStage.VERIFYING, 100, "Verifying SHA-256 & signing certificate..."))
            var expectedSha = update.expectedSha256Hex.trim().lowercase()
            if (expectedSha.length != 64 && update.sha256Url.isNotBlank()) {
                val body = downloadText(update.sha256Url)
                expectedSha = parseExpectedSha256ForAsset(body, safeFileName).orEmpty()
            }
            if (expectedSha.length != 64) {
                apkFile.delete()
                error("Missing SHA-256 checksum for release; refusing unverified install.")
            }

            val actualSha = computeFileSha256Hex(apkFile)
            if (!constantTimeHexEquals(actualSha, expectedSha)) {
                apkFile.delete()
                error("SHA-256 mismatch! Expected $expectedSha, got $actualSha")
            }

            val pm = context.packageManager
            val installedCerts = extractInstalledSigningCertDigests(pm, context.packageName)
            val candidateCerts = extractArchiveSigningCertDigests(pm, apkFile.absolutePath)
            if (!verifySigningCertMatch(installedCerts, candidateCerts)) {
                apkFile.delete()
                error("APK signing certificate mismatch! Update rejected.")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
                emit(
                    ApkUpdateInstallState(
                        stage = ApkInstallStage.AWAITING_PERMISSION,
                        progressPercent = 100,
                        message = "Verified SHA-256 ($actualSha). Grant 'Install unknown apps' then tap Install again.",
                        verifiedSha256 = actualSha
                    )
                )
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return@runCatching
            }

            emit(
                ApkUpdateInstallState(
                    stage = ApkInstallStage.INSTALLING,
                    progressPercent = 100,
                    message = "Verified SHA-256 & signature. Launching PackageInstaller...",
                    verifiedSha256 = actualSha
                )
            )
            installVerifiedApkViaSession(context, apkFile)
        }.onFailure { err ->
            runCatching { apkFile.delete() }
            emit(
                ApkUpdateInstallState(
                    stage = ApkInstallStage.FAILED,
                    progressPercent = 0,
                    message = err.message ?: "Verified update failed"
                )
            )
        }
    }

    private fun installVerifiedApkViaSession(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            FileInputStream(apkFile).use { input ->
                session.openWrite("pulsemesh-verified-update", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callbackIntent = Intent(context, ApkInstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        }
    }
}

class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                VerifiedApkUpdater.updateStatusFromReceiver(
                    ApkUpdateInstallState(
                        stage = ApkInstallStage.INSTALLED,
                        progressPercent = 100,
                        message = "PulseMesh updated successfully."
                    )
                )
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "PackageInstaller error ($status)"
                VerifiedApkUpdater.updateStatusFromReceiver(
                    ApkUpdateInstallState(
                        stage = ApkInstallStage.FAILED,
                        progressPercent = 0,
                        message = msg
                    )
                )
            }
        }
    }
}
