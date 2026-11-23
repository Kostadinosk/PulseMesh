// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MapPackInfo(
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String,
    val zoomMin: Int = 0,
    val zoomMax: Int = 16
)

/**
 * Manages offline `.mbtiles` and `.pmtiles` vector/raster packs under `filesDir/map_packs`,
 * supports XYZ->TMS MBTiles tile lookups, and bundles P2P ZIP export/import across devices.
 */
object OfflineMapPackManager {

    private const val PACK_DIR_NAME = "map_packs"

    fun getPacksDirectory(context: Context): File =
        File(context.filesDir, PACK_DIR_NAME).apply { mkdirs() }

    fun listInstalledPacks(context: Context): List<MapPackInfo> {
        val dir = getPacksDirectory(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.mapNotNull { inspectPackFile(it) }.sortedBy { it.fileName }
    }

    fun inspectPackFile(file: File): MapPackInfo? {
        if (!file.exists() || !file.isFile || file.length() < 16L) return null
        val lower = file.name.lowercase(Locale.US)
        val header = ByteArray(16)
        runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrNull() ?: return null

        val headerAscii = String(header, Charsets.US_ASCII)
        val format = when {
            lower.endsWith(".mbtiles") && headerAscii.startsWith("SQLite format 3") -> "MBTILES"
            lower.endsWith(".pmtiles") && headerAscii.startsWith("PMTiles") -> "PMTILES"
            else -> return null
        }

        val sha = computeSha256Hex(file)
        var zoomMin = 0
        var zoomMax = 16
        if (format == "MBTILES") {
            runCatching {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name, value FROM metadata WHERE name IN ('minzoom','maxzoom')", null).use { cur ->
                        while (cur.moveToNext()) {
                            val k = cur.getString(0)
                            val v = cur.getString(1)?.toIntOrNull()
                            if (k == "minzoom" && v != null) zoomMin = v
                            if (k == "maxzoom" && v != null) zoomMax = v
                        }
                    }
                }
            }
        }

        return MapPackInfo(
            fileName = file.name,
            format = format,
            sizeBytes = file.length(),
            sha256 = sha,
            zoomMin = zoomMin,
            zoomMax = zoomMax
        )
    }

    fun readMbtilesTile(packFile: File, z: Int, x: Int, yXyz: Int): ByteArray? {
        if (!packFile.exists() || z !in 0..22) return null
        val tmsY = (1 shl z) - 1 - yXyz
        return runCatching {
            SQLiteDatabase.openDatabase(packFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                    arrayOf(z.toString(), x.toString(), tmsY.toString())
                ).use { cur ->
                    if (cur.moveToFirst()) cur.getBlob(0) else null
                }
            }
        }.getOrNull()
    }

    fun readFirstMatchingMbtilesTile(context: Context, z: Int, x: Int, yXyz: Int): ByteArray? {
        val dir = getPacksDirectory(context)
        val packs = dir.listFiles()?.filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".mbtiles") }
            ?: return null
        for (p in packs) {
            val bytes = readMbtilesTile(p, z, x, yXyz)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    fun exportMapPackZip(context: Context, outFile: File): File {
        val dir = getPacksDirectory(context)
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            val files = dir.listFiles()?.filter {
                it.isFile && (it.name.lowercase(Locale.US).endsWith(".mbtiles") || it.name.lowercase(Locale.US).endsWith(".pmtiles"))
            }.orEmpty()
            val buf = ByteArray(16_384)
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { fis ->
                    while (true) {
                        val r = fis.read(buf)
                        if (r <= 0) break
                        zos.write(buf, 0, r)
                    }
                }
                zos.closeEntry()
            }
        }
        return outFile
    }

    fun importMapPackZip(context: Context, zipFile: File): Int {
        if (!zipFile.exists()) return 0
        val dir = getPacksDirectory(context)
        val dirCanon = dir.canonicalPath + File.separator
        var imported = 0
        val buf = ByteArray(16_384)
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val entryName = File(entry.name).name
                val lower = entryName.lowercase(Locale.US)
                if (!lower.endsWith(".mbtiles") && !lower.endsWith(".pmtiles")) {
                    zis.closeEntry()
                    continue
                }
                val dest = File(dir, entryName)
                if (!dest.canonicalPath.startsWith(dirCanon)) {
                    zis.closeEntry()
                    continue
                }
                FileOutputStream(dest).use { fos ->
                    while (true) {
                        val r = zis.read(buf)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                    }
                }
                if (inspectPackFile(dest) != null) {
                    imported++
                } else {
                    dest.delete()
                }
                zis.closeEntry()
            }
        }
        return imported
    }

    fun importPackFromUri(context: Context, uri: android.net.Uri, preferredName: String = ""): MapPackInfo? {
        val rawName = preferredName.ifBlank {
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "imported_pack.mbtiles"
        }
        val cleanName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val lower = cleanName.lowercase(Locale.US)
        if (!lower.endsWith(".mbtiles") && !lower.endsWith(".pmtiles")) {
            return null
        }
        val dest = File(getPacksDirectory(context), cleanName)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16_384)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                    }
                    output.flush()
                }
            } ?: return null
            val info = inspectPackFile(dest)
            if (info == null) {
                dest.delete()
            }
            info
        }.getOrNull()
    }

    private fun computeSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16_384)
        FileInputStream(file).use { fis ->
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
