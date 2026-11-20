package com.lsd.io

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Date
import java.util.Enumeration
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveLocation(val archive: File, val prefix: String = "") {
    val displayPath: String get() = "${archive.path}!/${prefix}"
}

/** ZIP metadata preview and plain TAR/ZIP creation. Browsing never extracts archive data. */
object ArchiveOps {
    const val ZIP = "zip"
    const val TAR = "tar"
    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_SEARCH_HITS = 3000
    private const val MAX_PREVIEW_ENTRIES = 3000
    private const val MAX_SCANNED_ENTRIES = 100_000

    /** List only metadata for the current archive folder (or recursive name-search results). */
    fun list(
        location: ArchiveLocation,
        showHidden: Boolean,
        sort: Int,
        reverse: Boolean,
        query: String? = null,
        cancelled: () -> Boolean = { false },
        onPartial: (Listing) -> Unit = {},
    ): Listing {
        val prefix = normalizePrefix(location.prefix)
        val hits = LinkedHashMap<String, Entry>()
        val needle = query?.lowercase(Locale.getDefault())
        var lastReported = 0
        var scanned = 0
        var truncated = false

        fun snapshot(partial: Boolean = false): Listing {
            val items = hits.values.toMutableList()
            items.sortWith(FileOps.comparator(sort, reverse))
            if (prefix.isNotEmpty() && needle == null) {
                val parent = prefix.trimEnd('/').substringBeforeLast('/', "")
                val parentPrefix = if (parent.isEmpty()) "" else "$parent/"
                items.add(0, archiveEntry(location.archive, parentPrefix, true, 0, 0, "..", isParent = true))
            }
            return Listing(items, hits.values.count { it.isDir }, hits.values.count { !it.isDir }, partial)
        }

        fun accept(rawName: String, isDir: Boolean, size: Long, modified: Long) {
            val oldSize = hits.size
            val normalized = normalizeMember(rawName, isDir) ?: return
            if (!normalized.startsWith(prefix)) return
            val remainder = normalized.removePrefix(prefix)
            if (remainder.isEmpty()) return

            if (needle == null) {
                val slash = remainder.indexOf('/')
                val leaf = if (slash >= 0) remainder.substring(0, slash) else remainder.trimEnd('/')
                if (leaf.isEmpty() || (!showHidden && leaf.startsWith('.'))) return
                val childIsDir = slash >= 0 || isDir
                val member = prefix + leaf + if (childIsDir) "/" else ""
                val name = leaf.substringAfterLast('/')
                hits.putIfAbsent(member, archiveEntry(location.archive, member, childIsDir, modified, if (childIsDir) 0 else size, name))
            } else {
                val name = normalized.trimEnd('/').substringAfterLast('/')
                if (name.isEmpty() || (!showHidden && name.startsWith('.')) || !name.lowercase(Locale.getDefault()).contains(needle)) return
                hits[normalized] = archiveEntry(location.archive, normalized, isDir, modified, if (isDir) 0 else size, name)
            }
            if (hits.size != oldSize && (hits.size == 1 || hits.size - lastReported >= 100)) {
                lastReported = hits.size
                onPartial(snapshot())
            }
        }

        when (location.archive.extension.lowercase()) {
            ZIP -> ZipFile(location.archive).use { zip ->
                val entries: Enumeration<out ZipEntry> = zip.entries()
                while (entries.hasMoreElements() && !cancelled()) {
                    if (scanned++ >= MAX_SCANNED_ENTRIES || hits.size >= if (needle == null) MAX_PREVIEW_ENTRIES else MAX_SEARCH_HITS) {
                        truncated = true
                        break
                    }
                    val entry = entries.nextElement()
                    accept(entry.name, entry.isDirectory, entry.size.coerceAtLeast(0), entry.time.coerceAtLeast(0))
                }
            }
            TAR -> TarArchiveInputStream(BufferedInputStream(FileInputStream(location.archive), BUFFER_SIZE), "UTF-8").use { tar ->
                while (!cancelled()) {
                    if (scanned++ >= MAX_SCANNED_ENTRIES || hits.size >= if (needle == null) MAX_PREVIEW_ENTRIES else MAX_SEARCH_HITS) {
                        truncated = true
                        break
                    }
                    val entry = tar.getNextEntry() ?: break // skips file bytes without extracting them
                    if (!entry.isFile && !entry.isDirectory) continue
                    accept(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate.time)
                }
            }
            else -> throw IOException("Unsupported archive type")
        }
        return snapshot(truncated)
    }

    fun childLocation(location: ArchiveLocation, member: String): ArchiveLocation =
        location.copy(prefix = normalizePrefix(member))

    private fun archiveEntry(
        archive: File,
        member: String,
        isDir: Boolean,
        modified: Long,
        size: Long,
        name: String,
        isParent: Boolean = false,
    ) = Entry(
        file = File("${archive.path}!/$member"),
        isDir = isDir,
        isParent = isParent,
        modified = modified,
        size = size,
        displayName = name,
        archiveMember = member,
    )

    private fun normalizePrefix(raw: String): String {
        val clean = normalizeMember(raw, isDir = true) ?: return ""
        return if (clean.isEmpty() || clean.endsWith('/')) clean else "$clean/"
    }

    /** Ignore absolute and parent-traversal names; this is preview only and writes no archive paths. */
    private fun normalizeMember(raw: String, isDir: Boolean): String? {
        var name = raw.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        if (name.startsWith('/') || name.contains('\u0000')) return null
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) return null
        if (parts.isEmpty()) return ""
        val normalized = parts.joinToString("/")
        return if (isDir || raw.endsWith('/')) "$normalized/" else normalized
    }

    /** Extract archive entries directly into the chosen folder, streaming each file to disk. */
    fun extract(archives: List<File>, destinationDir: File, progress: FileOps.Progress, tick: () -> Unit) {
        if (!destinationDir.isDirectory) throw IOException("Destination folder is unavailable")
        for (archive in archives) {
            try {
                progress.current = archive.name
                val count = when (archive.extension.lowercase()) {
                    ZIP -> extractZip(archive, destinationDir, progress, tick)
                    TAR -> extractTar(archive, destinationDir, progress, tick)
                    else -> throw IOException("Unsupported archive type: ${archive.name}")
                }
                progress.okItems += count
            } catch (t: Throwable) {
                progress.errors++
            }
        }
    }

    private fun extractZip(
        archive: File,
        destinationDir: File,
        progress: FileOps.Progress,
        tick: () -> Unit,
    ): Int {
        val directoryMap = HashMap<String, File>()
        var extracted = 0
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                progress.current = "${archive.name}: ${entry.name}"
                tick()
                val target = extractionTarget(destinationDir, entry.name, entry.isDirectory, directoryMap)
                if (target == null) {
                    progress.errors++
                    continue
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) progress.errors++ else extracted++
                } else {
                    try {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Can't create folder") }
                        zip.getInputStream(entry).use { input -> FileOutputStream(target).use { output -> copyEntry(input, output, progress, tick) } }
                        if (entry.time >= 0) target.setLastModified(entry.time)
                        extracted++
                    } catch (t: Throwable) {
                        target.delete()
                        progress.errors++
                    }
                }
            }
        }
        return extracted
    }

    private fun extractTar(
        archive: File,
        destinationDir: File,
        progress: FileOps.Progress,
        tick: () -> Unit,
    ): Int {
        val directoryMap = HashMap<String, File>()
        var extracted = 0
        TarArchiveInputStream(BufferedInputStream(FileInputStream(archive), BUFFER_SIZE), "UTF-8").use { tar ->
            while (true) {
                val entry = tar.getNextEntry() ?: break
                if (!entry.isFile && !entry.isDirectory) {
                    progress.errors++ // links and special files are deliberately not created
                    continue
                }
                progress.current = "${archive.name}: ${entry.name}"
                tick()
                val target = extractionTarget(destinationDir, entry.name, entry.isDirectory, directoryMap)
                if (target == null) {
                    progress.errors++
                    continue
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) progress.errors++ else extracted++
                } else {
                    try {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Can't create folder") }
                        FileOutputStream(target).use { output -> copyEntry(tar, output, progress, tick) }
                        target.setLastModified(entry.lastModifiedDate.time)
                        extracted++
                    } catch (t: Throwable) {
                        target.delete()
                        progress.errors++
                    }
                }
            }
        }
        return extracted
    }

    private fun copyEntry(input: java.io.InputStream, output: OutputStream, progress: FileOps.Progress, tick: () -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            progress.done += n
            tick()
        }
    }

    /** Resolve directory collisions consistently and never allow a member outside [root]. */
    private fun extractionTarget(root: File, rawName: String, isDir: Boolean, dirs: MutableMap<String, File>): File? {
        val normalized = normalizeMember(rawName, isDir) ?: return null
        val parts = normalized.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val canonicalRoot = root.canonicalPath
        val rootPath = if (canonicalRoot.endsWith(File.separator)) canonicalRoot else canonicalRoot + File.separator
        fun safe(file: File): Boolean = file.canonicalPath.startsWith(rootPath)

        var parent = root
        var originalPrefix = ""
        for (part in parts.dropLast(1)) {
            originalPrefix += "$part/"
            var child = dirs[originalPrefix]
            if (child == null) {
                child = File(parent, part)
                if (child.exists() && !child.isDirectory) child = FileOps.uniqueDest(parent, part)
                if (!safe(child)) return null
                if (!child.exists() && !child.mkdirs()) return null
                if (!child.isDirectory) return null
                dirs[originalPrefix] = child
            }
            parent = child
        }

        val leaf = parts.last()
        val fullKey = originalPrefix + leaf + if (isDir) "/" else ""
        if (isDir) {
            dirs[fullKey]?.let { return it }
            var target = File(parent, leaf)
            if (target.exists() && !target.isDirectory) target = FileOps.uniqueDest(parent, leaf)
            if (!safe(target)) return null
            if (!target.exists() && !target.mkdirs()) return null
            if (!target.isDirectory) return null
            dirs[fullKey] = target
            return target
        }

        var target = File(parent, leaf)
        if (target.exists()) target = FileOps.uniqueDest(parent, leaf)
        return target.takeIf { safe(it) }
    }

    /** Create a new archive beside the source items, streaming data through bounded buffers. */
    fun create(
        sources: List<File>,
        destinationDir: File,
        requestedName: String,
        format: String,
        progress: FileOps.Progress,
        tick: () -> Unit,
    ): File {
        if (sources.isEmpty()) throw IOException("No files to archive")
        if (!destinationDir.isDirectory) throw IOException("Destination folder is unavailable")
        val ext = when (format) {
            ZIP -> ".zip"
            TAR -> ".tar"
            else -> throw IOException("Unsupported archive type")
        }
        val cleanName = requestedName.trim().ifBlank { "archive" }
        val base = when {
            cleanName.endsWith(".zip", ignoreCase = true) -> cleanName.dropLast(4)
            cleanName.endsWith(".tar", ignoreCase = true) -> cleanName.dropLast(4)
            else -> cleanName
        }.ifBlank { "archive" }
        if (base.contains('/') || base.contains('\\') || base == "." || base == "..") {
            throw IOException("Invalid archive name")
        }
        val requested = base + ext
        if (sources.none { it.exists() }) throw IOException("Nothing to archive")
        progress.total = sources.sumOf { treeSize(it) }
        val partial = File.createTempFile(".lsd-archive-", ".partial", destinationDir)
        try {
            when (format) {
                ZIP -> createZip(sources, partial, progress, tick)
                TAR -> createTar(sources, partial, progress, tick)
            }
            val target = FileOps.uniqueDest(destinationDir, requested)
            if (!partial.renameTo(target)) throw IOException("Can't finish archive")
            progress.okItems = 1
            return target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    private fun walk(file: File, name: String, visit: (File, String) -> Unit) {
        if (FileOps.isLink(file)) throw IOException("Can't archive symbolic links: $name")
        if (file.isDirectory) {
            visit(file, name.trimEnd('/') + "/")
            val children = file.listFiles() ?: throw IOException("Can't read folder: $name")
            for (child in children.sortedBy { it.name }) walk(child, name.trimEnd('/') + "/" + child.name, visit)
        } else if (file.isFile) {
            visit(file, name)
        } else {
            throw IOException("Can't read: $name")
        }
    }

    private fun walkAll(sources: List<File>, visit: (File, String) -> Unit) {
        for (source in sources) walk(source, source.name, visit)
    }

    private fun treeSize(file: File): Long {
        if (FileOps.isLink(file)) throw IOException("Can't archive symbolic links: ${file.name}")
        if (!file.isDirectory) {
            if (!file.isFile) throw IOException("Can't read: ${file.name}")
            return file.length()
        }
        val children = file.listFiles() ?: throw IOException("Can't read folder: ${file.name}")
        return children.sumOf { treeSize(it) }
    }

    private fun createZip(
        sources: List<File>,
        output: File,
        progress: FileOps.Progress,
        tick: () -> Unit,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE)).use { zip ->
            val buffer = ByteArray(BUFFER_SIZE)
            walkAll(sources) { file, name ->
                progress.current = name
                val entry = ZipEntry(name).apply { time = file.lastModified() }
                if (file.isDirectory) {
                    entry.method = ZipEntry.STORED
                    entry.size = 0
                    entry.compressedSize = 0
                    entry.crc = 0
                    zip.putNextEntry(entry)
                    zip.closeEntry()
                    return@walkAll
                }
                val crc = CRC32()
                var length = 0L
                FileInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        length += n
                    }
                }
                entry.method = ZipEntry.STORED
                entry.size = length
                entry.compressedSize = length
                entry.crc = crc.value
                zip.putNextEntry(entry)
                val writtenCrc = CRC32()
                var written = 0L
                FileInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        writtenCrc.update(buffer, 0, n)
                        written += n
                        progress.done += n
                        tick()
                    }
                }
                zip.closeEntry()
                if (written != length || writtenCrc.value != crc.value) throw IOException("File changed while archiving: $name")
            }
        }
    }

    private fun createTar(
        sources: List<File>,
        output: File,
        progress: FileOps.Progress,
        tick: () -> Unit,
    ) {
        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE), "UTF-8").use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            walkAll(sources) { file, name ->
                progress.current = name
                val entry = TarArchiveEntry(name).apply {
                    size = if (file.isFile) file.length() else 0L
                    modTime = Date(file.lastModified())
                }
                tar.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            tar.write(buffer, 0, n)
                            progress.done += n
                            tick()
                        }
                    }
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }
}
