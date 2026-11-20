package com.lsd.io

import java.io.File
import java.nio.file.Files
import java.util.Locale

class Entry(
    val file: File,
    val isDir: Boolean,
    val isParent: Boolean,
    val modified: Long,
    val size: Long,
    val displayName: String = file.name,
    val archiveMember: String? = null,
)

class Listing(val items: List<Entry>, val folders: Int, val files: Int, val truncated: Boolean = false)

object FileOps {

    class Progress {
        @Volatile var total = 0L
        @Volatile var done = 0L
        @Volatile var current = ""
        @Volatile var errors = 0
        @Volatile var okItems = 0
    }

    const val SORT_NAME = 0
    const val SORT_SIZE = 1
    const val SORT_DATE = 2
    const val SORT_TYPE = 3

    /** Folders always come first; [sort] orders each group, [reverse] flips that order. */
    fun comparator(sort: Int, reverse: Boolean): Comparator<Entry> {
        val byName = Comparator<Entry> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.file.name, b.file.name)
        }
        val base: Comparator<Entry> = when (sort) {
            SORT_SIZE -> Comparator<Entry> { a, b ->
                val c = (if (a.isDir) 0L else a.size).compareTo(if (b.isDir) 0L else b.size)
                if (c != 0) c else byName.compare(a, b)
            }
            SORT_DATE -> Comparator<Entry> { a, b ->
                val c = a.modified.compareTo(b.modified)
                if (c != 0) c else byName.compare(a, b)
            }
            SORT_TYPE -> Comparator<Entry> { a, b ->
                val c = String.CASE_INSENSITIVE_ORDER.compare(
                    if (a.isDir) "" else a.file.extension,
                    if (b.isDir) "" else b.file.extension,
                )
                if (c != 0) c else byName.compare(a, b)
            }
            else -> byName
        }
        val within: Comparator<Entry> = if (reverse) base.reversed() else base
        return Comparator<Entry> { a, b ->
            if (a.isDir != b.isDir) (if (a.isDir) -1 else 1) else within.compare(a, b)
        }
    }

    fun list(dir: File, showHidden: Boolean, sort: Int = SORT_NAME, reverse: Boolean = false): Listing {
        val items = ArrayList<Entry>()
        var folders = 0
        var files = 0
        dir.listFiles()?.forEach { f ->
            if (!showHidden && f.name.startsWith(".")) return@forEach
            val isDir = f.isDirectory
            items.add(Entry(f, isDir, false, f.lastModified(), if (isDir) 0L else f.length()))
            if (isDir) folders++ else files++
        }
        items.sortWith(comparator(sort, reverse))
        dir.parentFile?.let { items.add(0, Entry(it, true, true, 0L, 0L)) }
        return Listing(items, folders, files)
    }

    /** Recursive, case-insensitive name search below [root]. Symlinked folders are not followed. */
    fun search(
        root: File,
        query: String,
        showHidden: Boolean,
        sort: Int,
        reverse: Boolean,
        cancelled: () -> Boolean,
    ): Listing {
        val q = query.lowercase(Locale.getDefault())
        val items = ArrayList<Entry>()
        var folders = 0
        var files = 0
        val stack = ArrayList<File>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && items.size < MAX_HITS && !cancelled()) {
            // Termux folders can contain symlink loops that cannot be detected: cap the walk
            if (root is SafFile && ++visited > MAX_SAF_DIRS) break
            val d = stack.removeAt(stack.size - 1)
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                if (!showHidden && f.name.startsWith(".")) continue
                val isDir = f.isDirectory
                if (f.name.lowercase(Locale.getDefault()).contains(q)) {
                    items.add(Entry(f, isDir, false, f.lastModified(), if (isDir) 0L else f.length()))
                    if (isDir) folders++ else files++
                    if (items.size >= MAX_HITS) break
                }
                if (isDir && !isLink(f)) stack.add(f)
            }
        }
        items.sortWith(comparator(sort, reverse))
        return Listing(items, folders, files)
    }

    private const val MAX_HITS = 3000
    private const val MAX_SAF_DIRS = 20000
    private const val MAX_DEPTH = 64

    fun isLink(f: File): Boolean {
        if (f is SafFile) return false
        return try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Throwable) {
            false
        }
    }

    /** Delete a file or folder tree. Symlinks are removed, never followed. Returns true if all gone. */
    fun deleteTree(f: File): Boolean {
        if (f is SafFile) return deleteSaf(f, 0)
        var ok = true
        if (f.isDirectory && !isLink(f)) {
            val kids = f.listFiles()
            if (kids == null) ok = false else for (k in kids) if (!deleteTree(k)) ok = false
        }
        return f.delete() && ok
    }

    /**
     * Termux folder delete. The document provider follows symlinks and cannot tell us which entries are links,
     * so every entry is unlinked first: that removes files, empty folders and symlinks (a symlink is removed,
     * its target is never entered). Only a folder that refuses to go is emptied, then removed.
     */
    private fun deleteSaf(f: SafFile, depth: Int): Boolean {
        if (f.delete()) return true
        if (depth > MAX_DEPTH || !f.isDirectory) return false
        val kids = f.listFiles() ?: return false
        var ok = true
        for (k in kids) {
            if (k is SafFile) {
                if (!deleteSaf(k, depth + 1)) ok = false
            } else {
                ok = false
            }
        }
        return f.delete() && ok
    }

    /** Folder stats: total bytes, file count, folder count (the folder itself is not counted). */
    class Stats(val bytes: Long, val files: Int, val folders: Int)

    fun stats(f: File): Stats {
        var bytes = 0L
        var files = 0
        var folders = 0
        val stack = ArrayList<File>()
        stack.add(f)
        while (stack.isNotEmpty()) {
            val d = stack.removeAt(stack.size - 1)
            val kids: Array<File>? = try {
                d.listFiles()
            } catch (_: Throwable) {
                null
            }
            if (kids == null) continue
            if (f is SafFile && folders + files > MAX_SAF_DIRS * 10) break
            for (k in kids) {
                if (k.isDirectory && !isLink(k)) {
                    folders++
                    stack.add(k)
                } else {
                    files++
                    bytes += k.length()
                }
            }
        }
        return Stats(bytes, files, folders)
    }

    fun sizeOf(f: File): Long = try {
        if (f.isDirectory) sizeTree(f, 0) else f.length()
    } catch (_: Exception) {
        0L
    }

    private fun sizeTree(d: File, depth: Int): Long {
        if (depth > MAX_DEPTH) return 0L
        val kids = d.listFiles() ?: return 0L
        var sum = 0L
        for (k in kids) {
            sum += if (k.isDirectory) (if (isLink(k)) 0L else sizeTree(k, depth + 1)) else k.length()
        }
        return sum
    }

    fun uniqueDest(dir: File, name: String): File {
        var f = Vfs.child(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = Vfs.child(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    /** Copy or move [srcs] into [destDir]. Name clashes are auto-renamed "name (1)". */
    fun transfer(srcs: List<File>, destDir: File, cut: Boolean, p: Progress, tick: () -> Unit) {
        if (!destDir.isDirectory) {
            p.errors += srcs.size
            return
        }
        val destCanon = destDir.canonicalPath
        for (src in srcs) {
            p.current = src.name
            tick()
            val srcCanon = src.canonicalPath
            // never copy a folder into itself
            if (src.isDirectory && (destCanon == srcCanon || destCanon.startsWith("$srcCanon/"))) {
                p.errors++
                continue
            }
            // moving into the folder it is already in: nothing to do
            if (cut && src.parentFile?.canonicalPath == destCanon) continue

            val dst = uniqueDest(destDir, src.name)
            if (cut) {
                val size = sizeOf(src)
                if (src.renameTo(dst)) {
                    p.done += size
                    p.okItems++
                    tick()
                    continue
                }
                val before = p.errors
                copyRec(src, dst, p, tick, 0)
                if (p.errors == before) {
                    if (deleteTree(src)) p.okItems++ else p.errors++
                }
            } else {
                val before = p.errors
                copyRec(src, dst, p, tick, 0)
                if (p.errors == before) p.okItems++
            }
        }
    }

    /** Copy a file or folder tree to exactly [dst] (used for rename fallbacks). */
    fun copyTree(src: File, dst: File, p: Progress, tick: () -> Unit) = copyRec(src, dst, p, tick, 0)

    private fun copyRec(src: File, dst: File, p: Progress, tick: () -> Unit, depth: Int) {
        if (depth > MAX_DEPTH) {
            p.errors++ // too deep: almost certainly a symlink loop
            return
        }
        try {
            if (isLink(src)) {
                p.errors++ // not copied; also keeps a "cut" from deleting the source
                return
            }
            if (src.isDirectory) {
                if (!dst.mkdirs() && !dst.isDirectory) {
                    p.errors++
                    return
                }
                val kids = src.listFiles()
                if (kids == null) {
                    p.errors++
                    return
                }
                for (k in kids) copyRec(k, Vfs.child(dst, k.name), p, tick, depth + 1)
                dst.setLastModified(src.lastModified())
            } else {
                p.current = src.name
                Vfs.openIn(src).use { input ->
                    Vfs.openOut(dst).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            p.done += n
                            tick()
                        }
                    }
                }
                dst.setLastModified(src.lastModified())
            }
        } catch (_: Throwable) {
            p.errors++
            try {
                if (dst.isFile) dst.delete()
            } catch (_: Exception) {
            }
        }
    }
}
