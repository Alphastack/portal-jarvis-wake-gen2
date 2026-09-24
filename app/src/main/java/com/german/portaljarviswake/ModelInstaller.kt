package com.german.portaljarviswake

import com.german.portaljarviswake.core.SafeZip
import java.io.*
import java.net.HttpsURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads only from Vosk's HTTPS model host and installs atomically from a staging directory. */
class ModelInstaller(private val root: File) {
    val model = File(root, "vosk-small-en-us")
    fun installed() = File(model, "am").isDirectory
    fun install(progress: (Int)->Unit) {
        if (installed()) return
        val zip=File(root,"model.zip.part"); val staging=File(root,"model.staging-${System.currentTimeMillis()}")
        root.mkdirs(); staging.mkdirs()
        try { val c=URL(MODEL_URL).openConnection() as HttpsURLConnection; c.connectTimeout=15_000; c.readTimeout=30_000; c.inputStream.use { input -> FileOutputStream(zip).use { out -> val b=ByteArray(8192); var n:Int; var total=0L; val size=c.contentLengthLong; while(input.read(b).also{n=it}>=0){out.write(b,0,n);total+=n;if(size>0)progress((total*100/size).toInt())} } }; unzip(zip, staging); val extracted=staging.listFiles()?.firstOrNull { File(it,"am").isDirectory } ?: throw IOException("Model archive has no model directory"); if(model.exists()) model.deleteRecursively(); if(!extracted.renameTo(model)) throw IOException("Cannot finalize model"); progress(100) } finally { zip.delete(); staging.deleteRecursively() }
    }
    private fun unzip(zip:File, to:File) { ZipInputStream(FileInputStream(zip)).use { z -> var e=z.nextEntry; while(e!=null){ if(!SafeZip.isSafe(e.name)) throw IOException("Unsafe archive entry"); val target=File(to,e.name); if(e.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); FileOutputStream(target).use { z.copyTo(it) } }; e=z.nextEntry } } }
    companion object { const val MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" }
}
