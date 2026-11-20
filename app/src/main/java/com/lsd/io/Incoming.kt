package com.lsd.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Files shared to lsd from other apps ("Send to lsd" in the share sheet).
 * They are copied into lsd's private cache folder; open it in lsd and Cut / Copy them wherever you like.
 */
object Incoming {

    /** Private temp folder that receives shared files. */
    fun dir(ctx: Context): File = File(ctx.cacheDir, "shared").also { it.mkdirs() }

    /** Share ("Send to lsd") or "Open with" -> lsd. */
    fun isIncoming(i: Intent): Boolean =
        i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE ||
            (i.action == Intent.ACTION_VIEW && i.data != null)

    class Result(val saved: Int, val failed: Int)

    /** Copy everything the intent carries into [dir]. Blocking: call from a background thread. */
    fun receive(ctx: Context, intent: Intent, onFile: (String) -> Unit): Result {
        val dir = dir(ctx)
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }

        var saved = 0
        var failed = 0
        for (u in uris) {
            if (saveUri(ctx.contentResolver, u, dir, onFile)) saved++ else failed++
        }

        // plain text shared with no file (a link, a note...): keep it as a .txt
        if (uris.isEmpty() && intent.action != Intent.ACTION_VIEW) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) {
                try {
                    val f = FileOps.uniqueDest(dir, "shared_text.txt")
                    onFile(f.name)
                    f.writeText(text)
                    saved++
                } catch (_: Throwable) {
                    failed++
                }
            }
        }
        return Result(saved, failed)
    }

    private fun saveUri(cr: ContentResolver, uri: Uri, dir: File, onFile: (String) -> Unit): Boolean {
        val name = cleanName(displayName(cr, uri), cr, uri)
        val dst = FileOps.uniqueDest(dir, name)
        onFile(dst.name)
        val opened = try {
            cr.openInputStream(uri)
        } catch (_: Throwable) {
            null
        }
        val input = opened ?: return false
        return try {
            input.use { ins -> FileOutputStream(dst).use { out -> ins.copyTo(out, 256 * 1024) } }
            true
        } catch (_: Throwable) {
            try {
                dst.delete()
            } catch (_: Throwable) {
            }
            false
        }
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && !c.isNull(i)) return c.getString(i)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return uri.lastPathSegment
    }

    /** Safe single path component; adds an extension from the MIME type when the name has none. */
    private fun cleanName(raw: String?, cr: ContentResolver, uri: Uri): String {
        var n = (raw ?: "").substringAfterLast('/').replace('\u0000', '_').trim()
        if (n.isEmpty() || n == "." || n == "..") n = "shared_file"
        if (n.length > 150) {
            val dot = n.lastIndexOf('.')
            val extLen = n.length - dot
            n = if (dot > 0 && extLen <= 16) n.substring(0, 150 - extLen) + n.substring(dot) else n.substring(0, 150)
        }
        if (!n.contains('.') || n.endsWith(".")) {
            val ext = try {
                cr.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            } catch (_: Throwable) {
                null
            }
            if (ext != null) n = n.trimEnd('.') + "." + ext
        }
        return n
    }
}
