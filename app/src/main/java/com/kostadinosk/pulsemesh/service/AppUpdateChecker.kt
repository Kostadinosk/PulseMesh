// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotesSnippet: String,
    val apkDownloadUrl: String,
    val releasePageUrl: String
)

/**
 * Lightweight, privacy-respecting GitHub Releases startup update checker for PulseMesh.
 *
 * Strictly skipped when Off-Grid Mode is active (`isInternetEnabled == false`).
 * Queries `https://api.github.com/repos/Kostadinosk/PulseMesh/releases/latest` once on startup
 * (or on manual button tap) and compares semantic version numbers (`major.minor.patch`).
 */
object AppUpdateChecker {

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/Kostadinosk/PulseMesh/releases/latest"
    private const val RELEASES_FALLBACK_URL = "https://github.com/Kostadinosk/PulseMesh/releases/latest"

    /**
     * Pure semantic version comparison (`isNewerVersion("v1.4.1", "1.4.0") == true`).
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val rParts = parseSemVer(remoteTag)
        val cParts = parseSemVer(currentVersion)
        if (rParts.isEmpty() || cParts.isEmpty()) return false
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val cv = cParts.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseSemVer(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split('.').mapNotNull { it.toIntOrNull() }
    }

    suspend fun checkForUpdate(currentVersion: String): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PulseMesh-Android/$currentVersion")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub API HTTP $code")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseReleaseJson(body, currentVersion)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    internal fun parseReleaseJson(jsonBody: String, currentVersion: String): AppUpdateInfo? {
        val obj = JSONObject(jsonBody)
        val tag = obj.optString("tag_name").trim()
        if (tag.isBlank() || !isNewerVersion(tag, currentVersion)) return null

        val htmlUrl = obj.optString("html_url").ifBlank { RELEASES_FALLBACK_URL }
        val title = obj.optString("name").ifBlank { "PulseMesh $tag" }
        val rawNotes = obj.optString("body").trim()
        val snippet = rawNotes
            .lineSequence()
            .map { it.trim().removePrefix("#").removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" • ")
            .take(180)

        var apkUrl = htmlUrl
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name").lowercase()
                val dl = a.optString("browser_download_url")
                if (name == "app-release.apk" && dl.isNotBlank()) {
                    apkUrl = dl
                    break
                } else if (name.endsWith(".apk") && !name.contains("debug") && dl.isNotBlank()) {
                    apkUrl = dl
                }
            }
        }

        return AppUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = tag.removePrefix("v").removePrefix("V"),
            releaseTitle = title,
            releaseNotesSnippet = snippet,
            apkDownloadUrl = apkUrl,
            releasePageUrl = htmlUrl
        )
    }
}
