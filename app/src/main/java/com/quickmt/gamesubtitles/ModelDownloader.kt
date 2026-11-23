package com.quickmt.gamesubtitles

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object ModelDownloader {
    private const val REPO_BASE =
        "https://huggingface.co/value-productions/quickmt-en-tr-int8/resolve/main"

    private val REQUIRED_FILES = listOf(
        "config.json",
        "source_vocabulary.json",
        "target_vocabulary.json",
        "src.spm.model",
        "tgt.spm.model",
        "model.bin"
    )

    // Approximate total bytes for smooth progress even before model.bin header arrives
    private const val APPROX_TOTAL_BYTES = 208_500_000L

    fun getModelDir(context: Context): File = File(context.filesDir, "quickmt-en-tr-int8")

    fun isModelReady(context: Context): Boolean {
        val dir = getModelDir(context)
        if (!File(dir, ".complete").exists()) return false
        return REQUIRED_FILES.all {
            val f = File(dir, it)
            f.exists() && f.length() > 0L
        }
    }

    data class Progress(
        val percent: Int,
        val downloadedMb: Float,
        val totalMb: Float,
        val speedMbs: Float,
        val currentFile: String
    )

    class CancelledException : Exception("İndirme iptal edildi.")

    fun download(
        context: Context,
        cancelFlag: AtomicBoolean,
        onProgress: (Progress) -> Unit
    ): File {
        val dir = getModelDir(context)
        dir.mkdirs()

        var bytesCompletedBefore = 0L
        val startTimeNs = System.nanoTime()
        var sessionStartBytes = 0L
        for (name in REQUIRED_FILES) {
            val f = File(dir, name)
            val part = File(dir, "$name.part")
            if (f.exists()) sessionStartBytes += f.length()
            else if (part.exists()) sessionStartBytes += part.length()
        }

        var lastEmitMs = 0L

        for (fileName in REQUIRED_FILES) {
            if (cancelFlag.get()) throw CancelledException()
            val dest = File(dir, fileName)
            if (dest.exists() && dest.length() > 0L) {
                bytesCompletedBefore += dest.length()
                continue
            }

            val part = File(dir, "$fileName.part")
            var resumePos = if (part.exists()) part.length() else 0L
            val url = URL("$REPO_BASE/$fileName")

            var conn = openConnectionFollowingRedirects(url, resumePos)
            try {
                val code = conn.responseCode
                if (code == 416) {
                    // Already completely downloaded in .part
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    bytesCompletedBefore += dest.length()
                    continue
                }
                if (code !in 200..299) {
                    throw RuntimeException("Sunucu hatası ($code): $fileName")
                }

                val append = (code == HttpURLConnection.HTTP_PARTIAL)
                if (!append) resumePos = 0L

                val contentLen = conn.contentLengthLong.coerceAtLeast(0L)
                val fileTotal = if (contentLen > 0L) resumePos + contentLen else 0L
                val estGrandTotal = maxOf(APPROX_TOTAL_BYTES, bytesCompletedBefore + fileTotal)

                var written = resumePos
                val buf = ByteArray(128 * 1024)

                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        while (true) {
                            if (cancelFlag.get()) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n

                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastEmitMs >= 150L) {
                                lastEmitMs = nowMs
                                val totalDone = bytesCompletedBefore + written
                                val elapsedSec = ((System.nanoTime() - startTimeNs) / 1e9).coerceAtLeast(0.05)
                                val speedMbs = ((totalDone - sessionStartBytes).coerceAtLeast(0L) / (1024.0 * 1024.0) / elapsedSec).toFloat()
                                val pct = ((totalDone * 100L) / estGrandTotal).toInt().coerceIn(0, 99)
                                onProgress(
                                    Progress(
                                        percent = pct,
                                        downloadedMb = totalDone / (1024f * 1024f),
                                        totalMb = estGrandTotal / (1024f * 1024f),
                                        speedMbs = speedMbs,
                                        currentFile = fileName
                                    )
                                )
                            }
                        }
                    }
                }

                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                bytesCompletedBefore += dest.length()
            } finally {
                conn.disconnect()
            }
        }

        File(dir, ".complete").writeText("ok")
        val finalMb = bytesCompletedBefore / (1024f * 1024f)
        onProgress(Progress(100, finalMb, finalMb, 0f, "Tamamlandı"))
        return dir
    }

    private fun openConnectionFollowingRedirects(startUrl: URL, resumePos: Long): HttpURLConnection {
        var current = startUrl
        repeat(6) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "QuickMTGameSubtitlesAndroid/1.0")
                if (resumePos > 0L) {
                    setRequestProperty("Range", "bytes=$resumePos-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = URL(current, loc)
            } else {
                return conn
            }
        }
        throw RuntimeException("Çok fazla yönlendirme (redirect).")
    }
}
