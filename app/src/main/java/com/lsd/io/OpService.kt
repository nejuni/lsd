package com.lsd.io

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors

/** Foreground service that runs copy / move jobs and shows a progress notification. */
class OpService : Service() {

    companion object {
        private const val CH = "lsd_progress"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2

        /** Called on the main thread after a job finishes. */
        @Volatile var onFinished: (() -> Unit)? = null

        fun start(ctx: Context, srcs: List<String>, dest: String, cut: Boolean) {
            val i = Intent(ctx, OpService::class.java)
                .putStringArrayListExtra("src", ArrayList(srcs))
                .putExtra("dest", dest)
                .putExtra("cut", cut)
            ctx.startForegroundService(i)
        }

        fun startArchive(ctx: Context, srcs: List<String>, destDir: String, name: String, format: String) {
            val i = Intent(ctx, OpService::class.java)
                .putStringArrayListExtra("src", ArrayList(srcs))
                .putExtra("dest", destDir)
                .putExtra("archiveName", name)
                .putExtra("archiveFormat", format)
            ctx.startForegroundService(i)
        }

        fun startExtract(ctx: Context, archives: List<String>, destDir: String) {
            val i = Intent(ctx, OpService::class.java)
                .putStringArrayListExtra("src", ArrayList(archives))
                .putExtra("dest", destDir)
                .putExtra("extract", true)
            ctx.startForegroundService(i)
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var nm: NotificationManager
    private var pending = 0 // main thread only

    override fun onCreate() {
        super.onCreate()
        Vfs.init(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "File operations", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() demands startForeground() quickly, even for a bad intent
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progress("lsd", "Preparing…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val src = intent?.getStringArrayListExtra("src")
        val dest = intent?.getStringExtra("dest")
        val archiveName = intent?.getStringExtra("archiveName")
        val archiveFormat = intent?.getStringExtra("archiveFormat")
        val extracting = intent?.getBooleanExtra("extract", false) ?: false
        if (src == null || dest == null) {
            if (pending == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val cut = intent?.getBooleanExtra("cut", false) ?: false
        pending++
        exec.execute { runJob(src, dest, cut, archiveName, archiveFormat, extracting) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }

    private fun runJob(
        src: List<String>, dest: String, cut: Boolean, archiveName: String?, archiveFormat: String?, extracting: Boolean,
    ) {
        Vfs.init(this)
        val files = src.map { Vfs.file(it) }.filter { it.exists() }
        val destDir = Vfs.file(dest)
        // archives are read / written with random access, which the Termux folder cannot offer
        val archiveOnTermux = (extracting || (archiveFormat != null && archiveName != null)) &&
            (destDir is SafFile || files.any { it is SafFile })
        val creatingArchive = archiveFormat != null && archiveName != null
        val noun = if (files.size == 1) "item" else "items"
        val title = if (extracting) "Extracting ${files.size} archive${if (files.size == 1) "" else "s"}"
        else if (creatingArchive) "Creating ${archiveFormat!!.uppercase()} archive"
        else (if (cut) "Moving " else "Copying ") + "${files.size} $noun"
        val p = FileOps.Progress()
        var output: File? = null
        var last = 0L
        val tick = {
            val now = SystemClock.elapsedRealtime()
            if (now - last > 400) {
                last = now
                val total = p.total
                val pct = if (total > 0) (p.done * 100 / total).toInt().coerceIn(0, 100) else 0
                nm.notify(ID_PROGRESS, progress(title, p.current, pct, total <= 0))
            }
        }
        try {
            nm.notify(ID_PROGRESS, progress(title, "Preparing…", 0, true))
            if (archiveOnTermux) {
                p.errors++
            } else if (extracting) {
                ArchiveOps.extract(files, destDir, p, tick)
            } else if (creatingArchive) {
                output = ArchiveOps.create(files, destDir, archiveName!!, archiveFormat!!, p, tick)
            } else {
                p.total = files.sumOf { FileOps.sizeOf(it) }
                FileOps.transfer(files, destDir, cut, p, tick)
            }
        } catch (_: Throwable) {
            p.errors++
        }

        Bg.main.post {
            pending--
            val ok = p.okItems
            val okNoun = if (ok == 1) "item" else "items"
            val text = when {
                extracting -> buildString {
                    append("Extracted $ok $okNoun")
                    if (p.errors > 0) append(" · ${p.errors} failed")
                }
                creatingArchive && output != null -> "Created ${output!!.name}"
                creatingArchive -> "Archive creation failed"
                else -> buildString {
                    append(if (cut) "Moved " else "Copied ")
                    append("$ok $okNoun")
                    if (p.errors > 0) append(" · ${p.errors} failed")
                }
            }
            nm.notify(ID_DONE, done(if (p.errors > 0) "Finished with errors" else "Done", text))
            if (extracting) {
                val toastText = if (p.errors == 0) "Extract completed" else "Extract completed with errors"
                Toast.makeText(applicationContext, toastText, Toast.LENGTH_SHORT).show()
            }
            if (pending == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            onFinished?.invoke()
        }
    }

    private fun progress(title: String, text: String, pct: Int, indeterminate: Boolean): Notification =
        Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_folder)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .build()

    private fun done(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_folder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}
