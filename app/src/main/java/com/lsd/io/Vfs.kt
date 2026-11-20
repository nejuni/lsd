package com.lsd.io

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Access to folders that plain java.io.File cannot reach.
 *
 * Termux keeps its files in /data/data/com.termux/files, which belongs to Termux's own Linux user, so no other
 * app can open them with normal file calls. Termux does publish them through Android's document picker
 * (authority com.termux.documents), and the document id of every entry there is simply its real path.
 * The user connects that folder once (see MainActivity.requestTermuxAccess); lsd keeps the permission, and
 * every path below the connected folder is then served by [SafFile], a java.io.File that talks to the
 * document provider. Because it IS a File, both panes, selection, copy / cut, delete, rename and the
 * editor work on it without knowing the difference.
 */
object Vfs {
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_AUTHORITY = "com.termux.documents"
    const val TERMUX_FILES = "/data/data/com.termux/files"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"

    private const val PREFS = "lsd"
    private const val KEY_TREE = "termuxTree"

    @Volatile private var appCtx: Context? = null
    @Volatile private var tree: Uri? = null

    /** Real path of the connected Termux folder (also its document id), or null when not connected. */
    @Volatile var rootId: String? = null
        private set

    /** Safe to call any number of times, from any entry point (activity, service). */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (tree != null) return
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return
        val uri = Uri.parse(saved)
        if (uri.authority != TERMUX_AUTHORITY) return
        val held = try {
            app.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (_: Throwable) {
            false
        }
        if (!held) return
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Throwable) {
            return
        }
        if (!id.startsWith("/")) return
        tree = uri
        rootId = id.trimEnd('/')
    }

    fun isGranted(): Boolean = tree != null && rootId != null

    /** Remember the folder the user picked for Termux. */
    fun grant(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        appCtx = app
        if (uri.authority != TERMUX_AUTHORITY) return false
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Throwable) {
            return false
        }
        if (!id.startsWith("/") || id.trimEnd('/').isEmpty()) return false
        tree = uri
        rootId = id.trimEnd('/')
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
        return true
    }

    /** True when [path] lies inside the connected Termux folder. */
    fun isSaf(path: String): Boolean {
        val root = rootId ?: return false
        return path == root || path.startsWith("$root/")
    }

    /** Turn a stored path string back into the right kind of File. */
    fun file(path: String): File = if (isSaf(path)) SafFile(path) else File(path)

    /** Like File(parent, name) but keeps Termux folders working. */
    fun child(parent: File, name: String): File =
        if (parent is SafFile) SafFile(parent.path.trimEnd('/') + "/" + name) else File(parent, name)

    fun openIn(f: File): InputStream {
        if (f is SafFile) {
            return resolver().openInputStream(docUri(f.path)) ?: throw IOException("Can't open " + f.name)
        }
        return FileInputStream(f)
    }

    /** Opens [f] for writing (truncating). A missing Termux file is created first. */
    fun openOut(f: File): OutputStream {
        if (f is SafFile) {
            if (!f.exists() && !f.createNewFile()) throw IOException("Can't create " + f.name)
            return resolver().openOutputStream(docUri(f.path), "wt") ?: throw IOException("Can't write " + f.name)
        }
        return FileOutputStream(f)
    }

    // ---------- document provider calls (used by SafFile) ----------

    private fun resolver() = (appCtx ?: throw IOException("lsd is not initialised")).contentResolver

    private fun docUri(id: String): Uri {
        val t = tree ?: throw IOException("Termux folder is not connected")
        return DocumentsContract.buildDocumentUriUsingTree(t, id)
    }

    fun queryMeta(id: String): SafMeta {
        try {
            val c = resolver().query(
                docUri(id),
                arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        val mime = if (c.isNull(0)) "" else c.getString(0)
                        val size = if (c.isNull(1)) 0L else c.getLong(1)
                        val modified = if (c.isNull(2)) 0L else c.getLong(2)
                        return SafMeta(true, mime == Document.MIME_TYPE_DIR, size, modified)
                    }
                } finally {
                    c.close()
                }
            }
        } catch (_: Throwable) {
        }
        return SafMeta(false, false, 0L, 0L)
    }

    fun listChildren(id: String): Array<File>? {
        val result = ArrayList<File>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree ?: return null, id)
            val cursor = resolver().query(
                uri,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            ) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val mime = if (c.isNull(1)) "" else c.getString(1)
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    val modified = if (c.isNull(3)) 0L else c.getLong(3)
                    result.add(SafFile(docId, SafMeta(true, mime == Document.MIME_TYPE_DIR, size, modified)))
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return result.toTypedArray()
    }

    fun create(parentId: String, name: String, dir: Boolean): Boolean = try {
        val mime = if (dir) Document.MIME_TYPE_DIR else mimeFor(name)
        DocumentsContract.createDocument(resolver(), docUri(parentId), mime, name) != null
    } catch (_: Throwable) {
        false
    }

    fun delete(id: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver(), docUri(id))
    } catch (_: Throwable) {
        false
    }

    fun rename(id: String, newName: String): Boolean = try {
        DocumentsContract.renameDocument(resolver(), docUri(id), newName) != null
    } catch (_: Throwable) {
        false
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

class SafMeta(val exists: Boolean, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * A file or folder inside the connected Termux folder. Its path is the real Termux path
 * (e.g. /data/data/com.termux/files/home/script.sh); every call is answered by the document provider.
 * Metadata is looked up once per instance, so a listing needs a single query.
 */
class SafFile(path: String, known: SafMeta? = null) : File(path) {

    @Volatile private var cached: SafMeta? = known

    private fun meta(): SafMeta {
        val c = cached
        if (c != null) return c
        val m = Vfs.queryMeta(path)
        cached = m
        return m
    }

    override fun exists(): Boolean = meta().exists
    override fun isDirectory(): Boolean = meta().let { it.exists && it.isDir }
    override fun isFile(): Boolean = meta().let { it.exists && !it.isDir }
    override fun length(): Long = meta().size
    override fun lastModified(): Long = meta().modified
    override fun canRead(): Boolean = meta().exists
    override fun canWrite(): Boolean = meta().exists
    override fun canExecute(): Boolean = false
    override fun setLastModified(time: Long): Boolean = false

    override fun getAbsolutePath(): String = path
    override fun getAbsoluteFile(): File = this
    override fun getCanonicalPath(): String = path
    override fun getCanonicalFile(): File = this

    /** The folder above. The top of the connected Termux folder leads back to shared storage. */
    override fun getParentFile(): File? {
        val root = Vfs.rootId
        if (root != null && path == root) return Environment.getExternalStorageDirectory()
        val i = path.lastIndexOf('/')
        if (i <= 0) return null
        return SafFile(path.substring(0, i))
    }

    override fun getParent(): String? = getParentFile()?.path

    override fun listFiles(): Array<File>? = Vfs.listChildren(path)

    override fun list(): Array<String>? = listFiles()?.map { it.name }?.toTypedArray()

    override fun createNewFile(): Boolean {
        val parent = getParentFile()
        if (parent !is SafFile || exists()) return false
        val ok = Vfs.create(parent.path, name, false)
        cached = null
        return ok
    }

    override fun mkdir(): Boolean {
        val parent = getParentFile()
        if (parent !is SafFile) return false
        val ok = Vfs.create(parent.path, name, true)
        cached = null
        return ok
    }

    override fun mkdirs(): Boolean {
        if (isDirectory()) return false
        val parent = getParentFile()
        if (parent is SafFile && !parent.isDirectory() && !parent.mkdirs()) return false
        return mkdir()
    }

    override fun delete(): Boolean {
        val ok = Vfs.delete(path)
        cached = null
        return ok
    }

    /**
     * Rename inside the same Termux folder. Moving between folders returns false, so callers fall back to
     * copy + delete (which is what FileOps.transfer does).
     */
    override fun renameTo(dest: File): Boolean {
        if (dest !is SafFile) return false
        val mine = getParentFile()?.path
        val theirs = dest.getParentFile()?.path
        if (mine == null || mine != theirs) return false
        if (Vfs.rename(path, dest.name)) {
            cached = null
            return true
        }
        // some providers have no rename: copy under the new name, then remove the original
        val p = FileOps.Progress()
        FileOps.copyTree(this, dest, p) { }
        cached = null
        if (p.errors == 0) return FileOps.deleteTree(this)
        FileOps.deleteTree(dest)
        return false
    }
}
