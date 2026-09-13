package com.fatemmo.mobile.assets

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Downloads and verifies asset packs from the server-hosted manifest,
 * storing them under [Context.getFilesDir]/assets — never inside the APK's
 * bundled `assets/` (that stays read-only client content). See
 * docs/FATE_MMO_MOBILE_ASSETS.md §download-system for the two-tier design
 * this implements (the operator's original "download what's needed now" vs
 * "download all" request).
 *
 * Every downloaded file is SHA-256 verified against the manifest before
 * being kept — this is the only thing standing between "asset server" and
 * "arbitrary code delivery to every install," since these bytes get parsed
 * as GAT/GND/image data by [com.fatemmo.mobile.world.GameMapView].
 */
class AssetDownloadManager(context: Context, private val baseUrl: String) {

    private val rootDir: File = File(context.filesDir, "assets")

    /** Where a downloaded copy of [relativePath] lives, once verified. */
    fun localFile(relativePath: String): File = File(rootDir, relativePath)

    /** True if a verified local copy already matches the manifest entry. */
    suspend fun isUpToDate(entry: AssetFileEntry): Boolean = withContext(Dispatchers.IO) {
        val f = localFile(entry.path)
        f.exists() && f.length() == entry.size && sha256Of(f) == entry.sha256
    }

    suspend fun fetchManifest(): AssetManifest = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/manifest.json")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("manifest.json HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            AssetManifest.parse(body)
        } finally {
            conn.disconnect()
        }
    }

    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val currentFile: String,
        val packBytesDone: Long,
        val packBytesTotal: Long
    )

    sealed class Outcome {
        data object Success : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * Downloads every file in [pack] that isn't already present with a
     * matching hash. Reports cumulative byte progress across the whole pack
     * via [onProgress]. Stops at the first failure (hash mismatch, HTTP
     * error, or I/O error) rather than leaving a partially-downloaded file
     * in place under the final name.
     */
    suspend fun downloadPack(pack: AssetPack, onProgress: (Progress) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            val totalBytes = pack.files.sumOf { it.size }
            var bytesDoneAcrossFiles = 0L

            for ((index, entry) in pack.files.withIndex()) {
                if (isUpToDate(entry)) {
                    bytesDoneAcrossFiles += entry.size
                    onProgress(Progress(index + 1, pack.files.size, entry.path, bytesDoneAcrossFiles, totalBytes))
                    continue
                }

                val target = localFile(entry.path)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.part")

                try {
                    val url = URL("$baseUrl/${entry.path}")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    try {
                        val code = conn.responseCode
                        if (code != HttpURLConnection.HTTP_OK) {
                            return@withContext Outcome.Failed("${entry.path}: HTTP $code")
                        }

                        val digest = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(conn.inputStream, digest).use { input ->
                            tmp.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var fileBytesDone = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    fileBytesDone += read
                                    onProgress(
                                        Progress(
                                            index + 1, pack.files.size, entry.path,
                                            bytesDoneAcrossFiles + fileBytesDone, totalBytes
                                        )
                                    )
                                }
                            }
                        }

                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actualHash != entry.sha256) {
                            tmp.delete()
                            return@withContext Outcome.Failed(
                                "${entry.path}: hash mismatch (expected ${entry.sha256}, got $actualHash)"
                            )
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                            return@withContext Outcome.Failed("${entry.path}: could not save downloaded file")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: IOException) {
                    tmp.delete()
                    return@withContext Outcome.Failed("${entry.path}: ${e.message}")
                }

                bytesDoneAcrossFiles += entry.size
            }

            Outcome.Success
        }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) != -1) { /* consume to feed digest */ }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
