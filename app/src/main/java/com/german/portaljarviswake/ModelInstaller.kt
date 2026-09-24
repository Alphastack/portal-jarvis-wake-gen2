package com.german.portaljarviswake

import com.german.portaljarviswake.core.SafeZipExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.net.ssl.HttpsURLConnection
import java.net.URL

class ModelInstaller(private val root: File) {
    val model = File(root, "vosk-small-en-us")
    fun installed() = File(model, "am").isDirectory
    fun install(progress: (Int) -> Unit) {
        if (installed()) return
        root.mkdirs(); root.listFiles { f -> f.name.startsWith("model.staging-") }?.forEach { it.deleteRecursively() }
        val archive = File(root, "model.zip.part"); val staging = File(root, "model.staging-${System.currentTimeMillis()}")
        try {
            val connection = URL(MODEL_URL).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000; connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("model server returned HTTP ${connection.responseCode}")
            try { connection.inputStream.use { input -> FileOutputStream(archive).use { output ->
                val buffer = ByteArray(8192); var total = 0L; var count: Int; val length = connection.contentLengthLong
                while (input.read(buffer).also { count = it } > 0) { total += count; if (total > MAX_ARCHIVE_BYTES) throw IOException("model archive exceeds limit"); output.write(buffer, 0, count); if (length > 0) progress((total * 100 / length).toInt().coerceAtMost(99)) }
            } } } finally { connection.disconnect() }
            val result = archive.inputStream().use { SafeZipExtractor.extract(it, staging, MAX_EXTRACTED_BYTES, MAX_ENTRIES) }
            if (!result.success) throw IOException("unsafe model archive: ${result.error}")
            val candidate = staging.listFiles()?.firstOrNull { File(it, "am").isDirectory } ?: throw IOException("model archive has no model directory")
            val backup = File(root, "model.previous"); if (backup.exists()) backup.deleteRecursively()
            if (model.exists() && !model.renameTo(backup)) throw IOException("cannot preserve existing model")
            if (!candidate.renameTo(model)) { backup.renameTo(model); throw IOException("cannot promote validated model") }
            backup.deleteRecursively(); progress(100)
        } finally { archive.delete(); staging.deleteRecursively() }
    }
    companion object { const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"; const val MAX_ARCHIVE_BYTES = 80L * 1024 * 1024; const val MAX_EXTRACTED_BYTES = 200L * 1024 * 1024; const val MAX_ENTRIES = 10_000 }
}
