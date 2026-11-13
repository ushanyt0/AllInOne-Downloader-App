package com.shancodex.downloader

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.DocumentsContract
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val folderPickerRequest = 4817
    private val downloadQueue = LinkedBlockingQueue<String>()
    @Volatile private var queueWorkerActive = false
    @Volatile private var pauseRequested = false
    @Volatile private var resumeRequested = false
    private val queueLock = java.lang.Object()
    private val processId = "allinone-download"
    private val notificationId = 7301
    private val notificationChannel = "download_status"

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
    @Volatile private var previewStatus = "idle"
    @Volatile private var previewTitle = ""
    @Volatile private var previewThumbnail = ""
    @Volatile private var previewDuration = ""
    @Volatile private var previewError = ""

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
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("if(typeof checkClipboardSuggestion==='function')checkClipboardSuggestion()", null)
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        createDownloadNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7302)
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
            updateDownloadNotification("Downloading", percent, true)
        } else {
            logLine = l.take(160)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.evaluateJavascript("if(typeof checkClipboardSuggestion==='function')checkClipboardSuggestion()", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderPickerRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                getSharedPreferences("download_settings", MODE_PRIVATE).edit()
                    .putString("tree_uri", uri.toString()).apply()
                val folderName = DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"
                web.evaluateJavascript("onDownloadLocationChanged(${JSONObject.quote(folderName)})", null)
            } catch (e: Throwable) {
                val message = e.message ?: "Could not access selected folder"
                web.evaluateJavascript("document.getElementById('settingsMsg').textContent=${JSONObject.quote(message)}", null)
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun getClipboardText(): String {
            return try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip: ClipData? = clipboard.primaryClip
                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
            } catch (e: Throwable) {
                ""
            }
        }

        @JavascriptInterface
        fun getDownloadLocation(): String {
            val value = getSharedPreferences("download_settings", MODE_PRIVATE).getString("tree_uri", null)
                ?: return "Download/AllInOneDownloader"
            return try {
                DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(value))?.name ?: "Selected folder"
            } catch (e: Throwable) {
                "Selected folder"
            }
        }

        @JavascriptInterface
        fun chooseDownloadFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
                startActivityForResult(intent, folderPickerRequest)
            }
        }

        @JavascriptInterface
        fun start(json: String) {
            enqueueDownload(json)
        }

        @JavascriptInterface
        fun enqueue(json: String) {
            enqueueDownload(json)
        }

        @JavascriptInterface
        fun queueCount(): Int {
            return downloadQueue.size + if (queueWorkerActive) 1 else 0
        }

        @JavascriptInterface
        fun pauseDownload() { pauseCurrentDownload() }

        @JavascriptInterface
        fun resumeDownload() {
            synchronized(queueLock) {
                if (status == "paused") {
                    resumeRequested = true
                    status = "running"
                    code = "resuming"
                    updateDownloadNotification("Resuming download", percent, true)
                    queueLock.notifyAll()
                }
            }
        }

        @JavascriptInterface
        fun listDownloads(): String = getDownloadsJson()

        @JavascriptInterface
        fun shareDownload(uri: String, mime: String) {
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime.ifBlank { "*/*" }
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share download"))
                } catch (e: Throwable) {
                    web.evaluateJavascript("showDownloadMessage(" + JSONObject.quote(e.message ?: "Cannot share file") + ")", null)
                }
            }
        }

        @JavascriptInterface
        fun deleteDownload(uri: String): Boolean {
            return try {
                val parsed = Uri.parse(uri)
                val treeValue = getSharedPreferences("download_settings", MODE_PRIVATE).getString("tree_uri", null)
                if (!treeValue.isNullOrBlank()) DocumentsContract.deleteDocument(contentResolver, parsed)
                else contentResolver.delete(parsed, null, null) > 0
            } catch (e: Throwable) { false }
        }

        @JavascriptInterface
        fun preview(url: String) {
            if (status == "running" || previewStatus == "running") return
            previewStatus = "running"
            previewTitle = ""
            previewThumbnail = ""
            previewDuration = ""
            previewError = ""
            Thread {
                try {
                    while (!ready && initError == null) Thread.sleep(300)
                    val ie = initError
                    if (ie != null) throw Exception("Engine init failed: " + ie)
                    val info = YoutubeDL.getInstance().getInfo(url)
                    previewTitle = info.title ?: "Untitled"
                    previewThumbnail = info.thumbnail ?: ""
                    previewDuration = info.duration?.toString() ?: ""
                    previewStatus = "done"
                } catch (e: Throwable) {
                    previewError = (e.message ?: e.toString()).take(220)
                    previewStatus = "error"
                }
            }.start()
        }

        @JavascriptInterface
        fun previewProgress(): String {
            val o = JSONObject()
            o.put("status", previewStatus)
            o.put("title", previewTitle)
            o.put("thumbnail", previewThumbnail)
            o.put("duration", previewDuration)
            o.put("error", previewError)
            return o.toString()
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

    private fun enqueueDownload(json: String) {
        var shouldStartWorker = false
        synchronized(this) {
            downloadQueue.offer(json)
            if (!queueWorkerActive) {
                queueWorkerActive = true
                shouldStartWorker = true
            }
        }
        if (shouldStartWorker) {
            val serviceIntent = Intent(this, DownloadKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            Thread {
                while (true) {
                    val next = synchronized(this) {
                        val item = downloadQueue.poll()
                        if (item == null) queueWorkerActive = false
                        item
                    } ?: break

                    val task = JSONObject(next)
                    if (!task.has("jobId")) task.put("jobId", java.util.UUID.randomUUID().toString())
                    do {
                        pauseRequested = false
                        status = "running"
                        percent = 0
                        code = "starting"
                        detail = ""
                        spd = ""
                        etaTxt = ""
                        sizeTxt = ""
                        logLine = ""
                        runDownload(task)
                        if (status == "paused") {
                            synchronized(queueLock) {
                                while (status == "paused" && !resumeRequested) queueLock.wait()
                                resumeRequested = false
                            }
                        }
                    } while (status == "running" && code == "resuming")

                    if (downloadQueue.isNotEmpty()) Thread.sleep(1200)
                }
                updateDownloadNotification("All downloads finished", 100, false)
                stopService(Intent(this@MainActivity, DownloadKeepAliveService::class.java))
            }.start()
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

            val job = File(cacheDir, "job_" + o.getString("jobId"))
            job.mkdirs()

            val req = YoutubeDLRequest(url)
            req.addOption("--no-mtime")
            req.addOption("--continue")
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

            YoutubeDL.getInstance().execute(req, processId) { progress, eta, line ->
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
            updateDownloadNotification("Download complete", 100, false)
            job.deleteRecursively()
        } catch (e: Throwable) {
            if (pauseRequested) {
                status = "paused"
                code = "paused"
                detail = "Download paused"
                updateDownloadNotification("Download paused", percent, true)
            } else {
                status = "error"
                code = "error"
                var extra = "  [yt-dlp " + engineVersion()
                if (updateError.isNotEmpty()) extra += "; update failed: " + updateError.take(80)
                extra += "]"
                detail = (e.message ?: e.toString()).takeLast(300) + extra
                updateDownloadNotification("Download failed", percent, false)
            }
        }
    }

    private fun pauseCurrentDownload() {
        if (status == "running" && code == "downloading") {
            pauseRequested = true
            try { YoutubeDL.getInstance().destroyProcessById(processId) }
            catch (_: Throwable) { status = "paused"; code = "paused" }
        }
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(notificationChannel, "Download status", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun updateDownloadNotification(title: String, progressValue: Int, ongoing: Boolean) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, notificationChannel) else Notification.Builder(this)
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("All-in-One Downloader")
                .setContentText(title)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
            if (ongoing) builder.setProgress(100, progressValue.coerceIn(0, 100), false)
            manager.notify(notificationId, builder.build())
        } catch (_: Throwable) { }
    }

    private fun getDownloadsJson(): String {
        val result = org.json.JSONArray()
        val treeValue = getSharedPreferences("download_settings", MODE_PRIVATE).getString("tree_uri", null)
        try {
            if (!treeValue.isNullOrBlank()) {
                val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeValue))
                fun addFolder(folder: DocumentFile) {
                    folder.listFiles().forEach { file ->
                        if (file.isDirectory) addFolder(file) else if (file.isFile) {
                            val item = JSONObject()
                            item.put("name", file.name ?: "Download")
                            item.put("uri", file.uri.toString())
                            item.put("mime", file.type ?: "*/*")
                            item.put("size", file.length())
                            result.put(item)
                        }
                    }
                }
                if (tree != null) addFolder(tree)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE)
                val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
                contentResolver.query(MediaStore.Files.getContentUri("external"), projection, selection,
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/AllInOneDownloader/"), MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        val item = JSONObject()
                        val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), cursor.getLong(idCol).toString())
                        item.put("name", cursor.getString(nameCol) ?: "Download")
                        item.put("uri", uri.toString())
                        item.put("mime", cursor.getString(mimeCol) ?: "*/*")
                        item.put("size", cursor.getLong(sizeCol))
                        result.put(item)
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AllInOneDownloader")
                dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    val item = JSONObject()
                    item.put("name", file.name)
                    item.put("uri", Uri.fromFile(file).toString())
                    item.put("mime", "*/*")
                    item.put("size", file.length())
                    result.put(item)
                }
            }
        } catch (_: Throwable) { }
        return result.toString()
    }

    private fun saveToDownloads(f: File, root: File) {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }

        val treeValue = getSharedPreferences("download_settings", MODE_PRIVATE).getString("tree_uri", null)
        if (!treeValue.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeValue))
                ?: throw Exception("Selected download folder is no longer available. Choose it again in Settings.")
            var destination = tree
            val relative = f.relativeTo(root).parent
            if (!relative.isNullOrBlank()) {
                relative.split(File.separatorChar).filter { it.isNotBlank() }.forEach { part ->
                    destination = destination.findFile(part)?.takeIf { it.isDirectory }
                        ?: destination.createDirectory(part)
                        ?: throw Exception("Cannot create folder: " + part)
                }
            }
            destination.findFile(f.name)?.delete()
            val outputFile = destination.createFile(mime, f.name)
                ?: throw Exception("Cannot create file in selected folder")
            contentResolver.openOutputStream(outputFile.uri, "w")?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: throw Exception("Cannot write to selected folder")
            return
        }

        val sub = "AllInOneDownloader"
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
