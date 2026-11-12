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
    @Volatile private var spd = ""
    @Volatile private var etaTxt = ""
    @Volatile private var sizeTxt = ""
    @Volatile private var logLine = ""
    @Volatile private var ready = false
    @Volatile private var initError: String? = null
    @Volatile private var updateError = ""

    private val speedRe = Regex("at\\s+~?\\s*([0-9.]+\\s*[KMGT]?i?B/s)")
    private val sizeRe = Regex("of\\s+~?\\s*([0-9.]+\\s*[KMGT]?i?B)")

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
                updateEngine()
                ready = true
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
            }
        }.start()
    }

    private fun updateEngine() {
        try {
            val prefs = getSharedPreferences("engine", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_update", 0L) > 6 * 60 * 60 * 1000L) {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                prefs.edit().putLong("last_update", now).apply()
            }
        } catch (e: Throwable) {
            updateError = e.message ?: e.toString()
        }
    }

    private fun engineVersion(): String {
        return try {
            YoutubeDL.getInstance().versionName(applicationContext) ?: "?"
        } catch (e: Throwable) {
            "?"
        }
    }

    private fun fmtEta(s: Long): String {
        val m = s / 60
        val r = s % 60
        return m.toString() + ":" + (if (r < 10) "0" else "") + r
    }

    private fun handleLine(line: String, eta: Long) {
        val l = line.trim()
        if (l.isEmpty()) return
        if (l.startsWith("[download]") && l.contains("%")) {
            val sp = speedRe.find(l)
            if (sp != null) spd = sp.groupValues[1].replace(" ", "")
            val sz = sizeRe.find(l)
            if (sz != null) sizeTxt = sz.groupValues[1].replace(" ", "")
            etaTxt = if (eta >= 0) fmtEta(eta) else ""
        } else {
            logLine = l.take(160)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(json: String) {
            if (status == "running") return
            status = "running"
            percent = 0
            code = "starting"
            detail = ""
            spd = ""
            etaTxt = ""
            sizeTxt = ""
            logLine = ""
            Thread { runDownload(JSONObject(json)) }.start()
        }

        @JavascriptInterface
        fun progress(): String {
            val o = JSONObject()
            o.put("status", status)
            o.put("percent", percent)
            o.put("code", code)
            o.put("detail", detail)
            o.put("speed", spd)
            o.put("eta", etaTxt)
            o.put("size", sizeTxt)
            o.put("log", logLine)
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
            req.addOption("--socket-timeout", "30")
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

            YoutubeDL.getInstance().execute(req, null) { progress, eta, line ->
                val pr = progress.toInt()
                if (pr >= 0) {
                    percent = pr.coerceIn(0, 100)
                    code = "downloading"
                }
                handleLine(line, eta)
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
            var extra = "  [yt-dlp " + engineVersion()
            if (updateError.isNotEmpty()) extra += "; update failed: " + updateError.take(80)
            extra += "]"
            detail = (e.message ?: e.toString()).takeLast(300) + extra
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
