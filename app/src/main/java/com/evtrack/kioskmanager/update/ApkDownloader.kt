package com.evtrack.kioskmanager.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads an APK from the CDN, streaming to disk while computing its SHA-256.
 * No third-party HTTP client — plain [HttpURLConnection] on [Dispatchers.IO].
 */
class ApkDownloader {

    /**
     * Download [url] into [dest], verifying SHA-256 if [expectedSha256] is provided.
     *
     * The bytes are streamed to a sibling temp file and the digest is computed on the
     * fly (no second read pass). Only on success is the temp file atomically moved to
     * [dest]; on any failure the temp file is deleted and a [Result.failure] returned.
     *
     * @return [Result] wrapping [dest] on success.
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: ((bytesRead: Long, total: Long) -> Unit)? = null,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            val tmp = File(dest.parentFile, dest.name + ".part")
            var conn: HttpURLConnection? = null
            try {
                dest.parentFile?.mkdirs()
                tmp.delete()

                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed: HTTP $code for $url")
                    )
                }

                // Content-Length may be -1 if the server doesn't send it (then % is unknown).
                val total = conn.contentLengthLong
                var read = 0L
                var lastReported = 0L
                onProgress?.invoke(0, total)

                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            // Throttle UI updates to ~1 MB steps to avoid flooding the main thread.
                            if (onProgress != null && read - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = read
                                onProgress(read, total)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress?.invoke(read, total)

                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256 != null && !expectedSha256.equals(actualSha, ignoreCase = true)) {
                    tmp.delete()
                    return@withContext Result.failure(
                        SecurityException(
                            "SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha"
                        )
                    )
                }

                // Success — move temp into final destination.
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                Log.i(TAG, "Downloaded ${dest.name} (${dest.length()} bytes, sha256=$actualSha)")
                Result.success(dest)
            } catch (e: Exception) {
                tmp.delete()
                Log.e(TAG, "Download error for $url", e)
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        private const val TAG = "ApkDownloader"

        /** Report download progress at most every ~1 MB. */
        private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
    }
}
