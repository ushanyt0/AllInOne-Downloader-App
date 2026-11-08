package com.shancodex.downloader

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView

    @Volatile private var status = "idle"
    @Volatile private var percent = 0
    @Volatile private var code = "ready"
    @Volatile private var detail = ""
    @Volatile private var ready = false
    @Volatile private var initError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0b0f1a")
        window.navigationBarColor = Color.parseColor("#0b0f1a")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0b0f1a"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(json: String) {
            if (status == "running") return
            status = "running"
            percent = 0
            code = "starting"
            detail = ""
            Thread { runDownload(JSONObject(json)) }.start()
        }

        @JavascriptInterface
        fun progress(): String {
            val o = JSONObject()
            o.put("status", status)
            o.put("percent", percent)
            o.put("code", code)
            o.put("detail", detail)
            return o.toString()
        }
    }

    private fun runDownload(o: JSONObject) {
        try {
            while (!ready && initError == null) Thread.sleep(300)
            val ie = initError
            if (ie != null) throw Exception("Init failed: " + ie)

            val url = o.getString("url")
            val mode = o.getString("mode")
            val quality = o.getString("quality")
            val playlist = o.optBoolean("playlist")
            val subs = o.optBoolean("subs")

            cacheDir.listFiles()?.filter { it.name.startsWith("job_") }?.forEach { it.deleteRecursively() }
            val job = File(cacheDir, "job_" + System.currentTimeMillis())
            job.mkdirs()

            val req = YoutubeDLRequest(url)
            req.addOption("--no-mtime")
            if (playlist) {
                req.addOption("-o", job.absolutePath + "/%(playlist_title).60s/%(playlist_index)s - %(title).100s.%(ext)s")
            } else {
                req.addOption("--no-playlist")
                req.addOption("-o", job.absolutePath + "/%(title).100s.%(ext)s")
            }
            if (mode == "audio") {
                req.addOption("-f", "bestaudio/best")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", quality + "K")
            } else {
                val fmt = if (quality == "best") {
                    "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
                } else {
                    "bv*[height<=" + quality + "][ext=mp4]+ba[ext=m4a]/bv*[height<=" + quality + "]+ba/b[height<=" + quality + "]"
                }
                req.addOption("-f", fmt)
                req.addOption("--merge-output-format", "mp4")
            }
            if (subs) {
                req.addOption("--write-subs")
                req.addOption("--write-auto-subs")
                req.addOption("--sub-langs", "en")
            }

            YoutubeDL.getInstance().execute(req, null) { progress, _, _ ->
                percent = progress.toInt().coerceIn(0, 100)
                code = "downloading"
            }

            code = "processing"
            val files = job.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) throw Exception("No file was created")
            for (f in files) {
                saveToDownloads(f, job)
            }
            status = "done"
            percent = 100
            code = "done"
            detail = ""
        } catch (e: Throwable) {
            status = "error"
            code = "error"
            detail = (e.message ?: e.toString()).takeLast(300)
        }
    }

    private fun saveToDownloads(f: File, root: File) {
        val rel = f.relativeTo(root).parent
        val sub = if (rel == null) "AllInOneDownloader" else "AllInOneDownloader/" + rel
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + sub)
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("Cannot save the file")
            contentResolver.openOutputStream(uri)!!.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            dir.mkdirs()
            f.copyTo(File(dir, f.name), true)
        }
    }
}
