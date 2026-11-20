package com.lsd.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Async thumbnails: APK icons, images and videos. */
object Thumbs {
    private const val SIZE = 160
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VID = setOf("mp4", "mkv", "webm", "3gp", "mov")

    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun canThumb(ext: String) = ext == "apk" || ext in IMG || ext in VID

    fun load(ctx: Context, e: Entry, ext: String, iv: ImageView) {
        val key = e.file.path + "|" + e.modified
        iv.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            apply(iv, hit, ext)
            return
        }
        val app = ctx.applicationContext
        pool.execute {
            if (iv.tag != key) return@execute // row was recycled already
            val bmp = try {
                decode(app, e.file, ext)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                Bg.main.post { if (iv.tag == key) apply(iv, bmp, ext) }
            }
        }
    }

    private fun apply(iv: ImageView, bmp: Bitmap, ext: String) {
        iv.imageTintList = null
        val pad = if (ext == "apk") iv.dp(4) else 0
        iv.setPadding(pad, pad, pad, pad)
        iv.scaleType = if (ext == "apk") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(bmp)
    }

    private fun decode(ctx: Context, f: File, ext: String): Bitmap? = when {
        ext == "apk" -> apkIcon(ctx, f)
        ext in IMG -> decodeImage(f)
        ext in VID -> ThumbnailUtils.createVideoThumbnail(f, Size(SIZE, SIZE), null)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun apkIcon(ctx: Context, f: File): Bitmap? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        val d: Drawable = ai.loadIcon(pm) ?: return null
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 128, 128)
        d.draw(Canvas(b))
        return b
    }

    private fun decodeImage(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= SIZE && o.outHeight / (s * 2) >= SIZE) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
