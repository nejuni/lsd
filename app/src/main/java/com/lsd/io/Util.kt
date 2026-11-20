package com.lsd.io

import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Shared background executor (directory listing) and main-thread handler. */
object Bg {
    val io = Executors.newSingleThreadExecutor()
    val archiveIo = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())
}

fun View.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private val dateFmt = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)

fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

fun fmtDateFull(ms: Long): String =
    SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date(ms))

fun fmtSize(b: Long): String {
    if (b < 1024) return "${b}B"
    val units = arrayOf("K", "M", "G", "T")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < 3) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, if (v >= 100) "%.0f%s" else "%.1f%s", v, units[i])
}

fun fmtGb(b: Long): String = String.format(Locale.US, "%.2fG", b / 1024.0 / 1024.0 / 1024.0)
