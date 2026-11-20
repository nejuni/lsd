package com.lsd.io

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.provider.DocumentsContract
import android.system.Os
import android.system.StructStat
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.window.OnBackInvokedDispatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    /** Hidden files are shown by default. */
    var showHidden = true
        private set

    private lateinit var left: Pane
    private lateinit var right: Pane
    private lateinit var active: Pane
    private var panesReady = false
    private var askedNotif = false
    private var openSharedWhenReady = false
    private val storageLocations = LinkedHashSet<String>()
    private val STORAGE_PICKER_REQUEST = 2401
    private val TERMUX_PICKER_REQUEST = 2402

    private lateinit var pathText: TextView
    private lateinit var infoText: TextView
    private lateinit var permPanel: View
    private lateinit var barNav: View
    private lateinit var barSel: View

    private companion object {
        const val RADIO_BASE = 7000
    }

    private val rootPath: String get() = Environment.getExternalStorageDirectory().path

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Vfs.init(this)
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.activity_main)

        pathText = findViewById(R.id.pathText)
        infoText = findViewById(R.id.infoText)
        permPanel = findViewById(R.id.permPanel)
        barNav = findViewById(R.id.barNav)
        barSel = findViewById(R.id.barSel)

        // tap the address bar -> copy the current folder path
        findViewById<View>(R.id.pathBox).setOnClickListener {
            if (panesReady) {
                val path = active.displayPath
                copyText(if (path.length > 1) path.trimEnd('/') else path)
            }
        }

        // Edge-to-edge insets: header gets the status bar, bottom bar the nav bar.
        val root = findViewById<View>(R.id.root)
        val header = findViewById<View>(R.id.header)
        val bottom = findViewById<View>(R.id.bottomContainer)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(b.left, 0, b.right, 0)
            header.setPadding(0, b.top, 0, 0)
            bottom.setPadding(0, 0, 0, b.bottom)
            ins
        }
        root.requestApplyInsets()

        findViewById<View>(R.id.btnGrant).setOnClickListener { requestAllFilesAccess() }

        findViewById<View>(R.id.btnBack).setOnClickListener { if (panesReady) active.goBack() }
        findViewById<View>(R.id.btnFwd).setOnClickListener { if (panesReady) active.goForward() }
        findViewById<View>(R.id.btnUp).setOnClickListener { if (panesReady) active.goUp() }
        // "+" : new file / folder in the active pane
        findViewById<View>(R.id.btnAdd).setOnClickListener { showCreateDialog() }
        // arrows: show the active pane's folder in the other pane too
        findViewById<View>(R.id.btnSwap).setOnClickListener { mirrorToOtherPane() }

        findViewById<View>(R.id.btnCopy).setOnClickListener { stash(false) }
        findViewById<View>(R.id.btnCut).setOnClickListener { stash(true) }
        findViewById<View>(R.id.btnAll).setOnClickListener { if (panesReady) active.selectAll() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { if (panesReady) active.clearSelection() }

        findViewById<View>(R.id.btnMenu).setOnClickListener { v ->
            if (!panesReady) return@setOnClickListener
            PopupMenu(this, v).apply {
                // places: reachable from either pane (the menu acts on the active pane)
                var order = 0
                menu.add(0, 10, order++, "Internal storage")
                val cards = sdCards()
                cards.forEachIndexed { index, card -> menu.add(0, 1000 + index, order++, card.first) }
                menu.add(
                    0, 20, order++,
                    if (Vfs.isGranted()) "Termux private folder" else "Termux private folder (connect)"
                )
                menu.add(1, 1, order++, "Select all")
                menu.add(1, 2, order++, if (showHidden) "Hide hidden files" else "Show hidden files")
                menu.add(1, 3, order++, "Add local storage…")
                val locations = loadStorageLocations().map(::File).filter { it.isDirectory }
                locations.forEachIndexed { index, folder ->
                    menu.add(2, 100 + index, order++, folder.name.ifEmpty { folder.path })
                }
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        10 -> active.navigate(File(rootPath))
                        20 -> openTermux()
                        1 -> active.selectAll()
                        2 -> {
                            showHidden = !showHidden
                            left.load()
                            right.load()
                        }
                        3 -> addLocalStorage()
                        in 100..999 -> {
                            val paths = loadStorageLocations().map(::File).filter { it.isDirectory }
                            val index = it.itemId - 100
                            if (index in paths.indices) active.navigate(paths[index])
                        }
                        in 1000..1099 -> {
                            val volumes = sdCards()
                            val index = it.itemId - 1000
                            if (index in volumes.indices) active.navigate(volumes[index].second)
                        }
                    }
                    true
                }
                show()
            }
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { v ->
            if (!panesReady) return@setOnClickListener
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, "Search")
                menu.add(0, 2, 1, "Select all")
                menu.add(0, 3, 2, "Sort")
                menu.add(0, 4, 3, if (isHome(active)) "Remove home" else "Set as Home")
                menu.add(0, 5, 4, "Shared files")
                menu.add(0, 6, 5, "Refresh")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> showSearchDialog()
                        2 -> active.selectAll()
                        3 -> showSortDialog()
                        4 -> toggleHome()
                        5 -> showSharedFolder()
                        6 -> {
                            left.load()
                            right.load()
                        }
                    }
                    true
                }
                show()
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        OpService.onFinished = {
            if (panesReady) {
                left.load()
                right.load()
            }
        }

        // launched from another app's share sheet
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Environment.isExternalStorageManager()) {
            permPanel.visibility = View.GONE
            if (!panesReady) initPanes() else {
                left.load()
                right.load()
            }
            askNotificationPermission()
        } else {
            permPanel.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        OpService.onFinished = null
        super.onDestroy()
    }

    private fun initPanes() {
        left = Pane(this, findViewById<RecyclerView>(R.id.rvLeft), findViewById(R.id.indLeft), homeOf(true))
        right = Pane(this, findViewById<RecyclerView>(R.id.rvRight), findViewById(R.id.indRight), homeOf(false))
        active = left
        restoreHomeSort(left)
        restoreHomeSort(right)
        panesReady = true
        refreshIndicators()
        left.load(true)
        right.load(true)
        onSelectionChanged()
        if (openSharedWhenReady) {
            openSharedWhenReady = false
            showSharedFolder()
        }
    }

    // ---------- files shared from other apps ----------

    /**
     * "Send to lsd" (share) or "Open with" -> lsd: copy the file(s) into the private temp folder,
     * then show that folder.
     */
    private fun handleShare(i: Intent?) {
        if (i == null || !Incoming.isIncoming(i)) return
        // a file lsd itself offered to the system (tapping a file inside lsd): it is already here
        if (i.action == Intent.ACTION_VIEW && i.data?.authority == "$packageName.fileprovider") {
            toast("This file is already in lsd")
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Receiving")
            .setMessage("Saving shared files…")
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            val r = try {
                Incoming.receive(this, i) { name -> runOnUiThread { dlg.setMessage(name) } }
            } catch (_: Throwable) {
                Incoming.Result(0, 1)
            }
            runOnUiThread {
                try {
                    dlg.dismiss()
                } catch (_: Throwable) {
                }
                if (r.saved > 0) {
                    toast(
                        "Received ${r.saved} " + (if (r.saved == 1) "file" else "files") +
                            (if (r.failed > 0) ", ${r.failed} failed" else "")
                    )
                    if (panesReady) showSharedFolder() else openSharedWhenReady = true
                } else {
                    toast(if (r.failed > 0) "Could not receive the shared file" else "Nothing to receive")
                }
            }
        }.start()
    }

    /** Show the private folder that holds shared files in the active pane. */
    private fun showSharedFolder() {
        if (!panesReady) return
        active.navigate(Incoming.dir(this))
    }

    // ---------- permissions ----------

    private fun requestAllFilesAccess() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun loadStorageLocations(): List<String> = getSharedPreferences("lsd", MODE_PRIVATE)
        .getStringSet("storageLocations", emptySet())
        ?.toList()?.sorted() ?: emptyList()

    private fun addLocalStorage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, STORAGE_PICKER_REQUEST)
        } catch (_: ActivityNotFoundException) {
            toast("Folder picker is unavailable")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TERMUX_PICKER_REQUEST) {
            handleTermuxResult(resultCode, data)
            return
        }
        if (requestCode != STORAGE_PICKER_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = try { localStoragePath(uri) } catch (_: Throwable) { null }
        if (path == null || !File(path).isDirectory) {
            toast("Choose a folder on device storage")
            return
        }
        val prefs = getSharedPreferences("lsd", MODE_PRIVATE)
        val saved = prefs.getStringSet("storageLocations", emptySet()).orEmpty().toMutableSet()
        saved.add(File(path).canonicalPath)
        prefs.edit().putStringSet("storageLocations", saved).apply()
        if (panesReady) active.navigate(File(path))
        toast("Local storage location added")
    }

    /** Convert Android's external-storage picker IDs to the corresponding shared-storage path. */
    private fun localStoragePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val external = getExternalFilesDirs(null).filterNotNull().firstOrNull { candidate ->
                val marker = "/Android/data/"
                candidate.path.contains(marker) && candidate.path.substringBefore(marker).endsWith("/$volume", ignoreCase = true)
            }
            external?.let { File(it.path.substringBefore("/Android/data/")) }
                ?: File("/storage/$volume").takeIf { it.isDirectory }
        } ?: return null
        val selected = if (relative.isEmpty()) volumeRoot else File(volumeRoot, relative)
        val root = volumeRoot.canonicalPath.trimEnd(File.separatorChar)
        val result = selected.canonicalPath
        return result.takeIf { selected.isDirectory && (it == root || it.startsWith(root + File.separator)) }
    }

    // ---------- SD card / Termux private folder ----------

    /** Mounted removable volumes (SD card, USB drive) as label to root folder. */
    private fun sdCards(): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        try {
            val sm = getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (v.isPrimary || v.isEmulated) continue
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = v.directory ?: continue
                val label = v.getDescription(this)
                result.add(Pair(if (label.isNullOrBlank()) dir.name else label, dir))
            }
        } catch (_: Throwable) {
        }
        if (result.isEmpty()) {
            // fallback: the app-specific folders of every volume reveal each volume's root
            try {
                val marker = "/Android/data/"
                for (d in getExternalFilesDirs(null)) {
                    if (d == null) continue
                    val i = d.path.indexOf(marker)
                    if (i <= 0) continue
                    val volumeRoot = File(d.path.substring(0, i))
                    if (volumeRoot.path == rootPath || volumeRoot.path.startsWith("/storage/emulated")) continue
                    if (volumeRoot.isDirectory) result.add(Pair(volumeRoot.name, volumeRoot))
                }
            } catch (_: Throwable) {
            }
        }
        return result
    }

    /** Open Termux's private folder in the active pane, asking for access the first time. */
    private fun openTermux() {
        if (!panesReady) return
        val root = Vfs.rootId
        if (!Vfs.isGranted() || root == null) {
            requestTermuxAccess()
            return
        }
        val target = if (Vfs.isSaf(Vfs.TERMUX_HOME)) Vfs.TERMUX_HOME else root
        active.navigate(Vfs.file(target))
    }

    /** One-time grant: the system folder picker opens on Termux; the user taps "Use this folder". */
    private fun requestTermuxAccess() {
        val installed = try {
            packageManager.getPackageInfo(Vfs.TERMUX_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
        if (!installed) {
            toast("Termux is not installed")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(Vfs.TERMUX_AUTHORITY, Vfs.TERMUX_HOME)
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        toast("Pick the Termux folder, then tap \"Use this folder\"")
        try {
            startActivityForResult(intent, TERMUX_PICKER_REQUEST)
        } catch (_: ActivityNotFoundException) {
            toast("Folder picker is unavailable")
        }
    }

    private fun handleTermuxResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.authority != Vfs.TERMUX_AUTHORITY) {
            toast("Please choose the Termux folder")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        if (!Vfs.grant(this, uri)) {
            toast("Can't use that folder")
            return
        }
        toast("Termux folder connected")
        openTermux()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---------- panes / header ----------

    fun setActive(p: Pane) {
        if (!panesReady || active === p) return
        val old = active
        active = p
        old.clearSelection()
        refreshIndicators()
        onSelectionChanged()
    }

    private fun refreshIndicators() {
        val on = getColor(R.color.accent)
        left.indicator.setBackgroundColor(if (active === left) on else 0)
        right.indicator.setBackgroundColor(if (active === right) on else 0)
    }

    fun onPaneChanged(p: Pane) {
        if (panesReady && p === active) updateHeader()
    }

    fun onSelectionChanged() {
        if (!panesReady) return
        val sel = active.selected.isNotEmpty()
        barSel.visibility = if (sel) View.VISIBLE else View.GONE
        barNav.visibility = if (sel) View.GONE else View.VISIBLE
        updateHeader()
    }

    private fun updateHeader() {
        val path = active.displayPath
        pathText.text = if (path.endsWith("/")) path else "$path/"
        infoText.text = if (active.selected.isNotEmpty()) {
            "${active.selected.size} selected"
        } else if (active.searching) {
            "Search \"${active.searchQuery}\": ${active.folders + active.files} found"
        } else if (active.isArchivePreview) {
            "Folders: ${active.folders}  Files: ${active.files}" +
                (if (active.archiveTruncated) "  Preview limited" else "")
        } else {
            "Folders: ${active.folders}  Files: ${active.files}  Disk: ${disk()}"
        }
    }

    /** Used / total space of the volume the active pane is on (Termux's folder lives on the app data partition). */
    private fun disk(): String {
        val d = if (panesReady) active.dir else null
        val first = if (d == null || d is SafFile) filesDir.path else d.path
        for (p in listOf(first, rootPath)) {
            try {
                val sf = StatFs(p)
                val total = sf.blockCountLong * sf.blockSizeLong
                val free = sf.availableBlocksLong * sf.blockSizeLong
                return "${fmtGb(total - free)}/${fmtGb(total)}"
            } catch (_: Exception) {
            }
        }
        return "?"
    }

    // ---------- copy / cut ----------

    /** Selection-bar Copy / Cut buttons. */
    private fun stash(cut: Boolean) {
        if (!panesReady) return
        val f = active.selectedFiles()
        if (f.isEmpty()) {
            toast("Select files first (long-press)")
            return
        }
        askTransfer(f, cut)
    }

    /**
     * Cut / Copy: ask whether to move / copy [f] into the folder the OTHER pane is showing, and do it on OK.
     * Cancel leaves everything (including the selection) as it was.
     */
    private fun askTransfer(f: List<File>, cut: Boolean) {
        if (!panesReady || f.isEmpty()) return
        val dest = (if (active === left) right else left).dir
        if (cut && f.all { it.parentFile?.path == dest.path }) {
            toast("Open another folder in the other pane first")
            return
        }
        val what = if (f.size == 1) (if (f[0].isDirectory) "folder " else "file ") + f[0].name else "${f.size} items"
        val destPath = if (dest.path.endsWith("/")) dest.path else dest.path + "/"
        AlertDialog.Builder(this)
            .setTitle(if (cut) "Move" else "Copy")
            .setMessage("Do you want to ${if (cut) "move" else "copy"} $what into $destPath ?")
            .setPositiveButton("OK") { _, _ ->
                if (panesReady) active.clearSelection()
                OpService.start(this, f.map { it.path }, dest.path, cut)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isArchiveFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf(ArchiveOps.ZIP, ArchiveOps.TAR)

    private fun askExtract(pane: Pane, archives: List<File>) {
        if (!panesReady || archives.isEmpty() || archives.any { !isArchiveFile(it) }) return
        val destination = if (pane === left) right.dir else left.dir
        if (destination is SafFile || archives.any { it is SafFile }) {
            toast("Extracting to or from the Termux folder isn't supported. Use shared storage.")
            return
        }
        val what = if (archives.size == 1) archives[0].name else "${archives.size} archives"
        val path = if (destination.path.endsWith("/")) destination.path else destination.path + "/"
        AlertDialog.Builder(this)
            .setTitle("Extract")
            .setMessage("Extract $what into $path?")
            .setPositiveButton("Extract") { _, _ ->
                pane.clearSelection()
                OpService.startExtract(this, archives.map { it.path }, destination.path)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- swipe menus ----------

    /** The swiped row, or the whole selection when that row is part of one. */
    private fun swipeTargets(pane: Pane, e: Entry): List<File> =
        if (pane.selected.contains(e.file.path)) pane.selectedFiles() else listOf(e.file)

    /**
     * Swipe LEFT: information about the swiped file / folder. If the row is part of a multi-selection,
     * shows the combined info of everything selected instead.
     */
    fun showSwipeInfo(pane: Pane, e: Entry) {
        val targets = swipeTargets(pane, e)
        if (targets.size > 1) showMultiInfo(targets) else showInfo(targets[0])
    }

    /** Swipe RIGHT: Cut / Copy / Rename / Delete / File information for the row (or the whole selection it is in). */
    fun showItemMenu(pane: Pane, e: Entry) {
        val targets = swipeTargets(pane, e)
        // Rename only makes sense for one item
        val labels = ArrayList<String>()
        labels.add("Cut")
        labels.add("Copy")
        if (targets.all { isArchiveFile(it) }) labels.add("Extract to")
        labels.add("Add to")
        if (targets.size == 1) labels.add("Rename")
        labels.add("Delete")
        labels.add("File information")
        AlertDialog.Builder(this)
            .setTitle(if (targets.size > 1) "${targets.size} items" else e.displayName)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "Cut" -> askTransfer(targets, true)
                    "Copy" -> askTransfer(targets, false)
                    "Extract to" -> askExtract(pane, targets)
                    "Add to" -> showArchiveFormatDialog(pane, targets)
                    "Rename" -> showRenameDialog(targets[0])
                    "Delete" -> confirmDelete(targets)
                    "File information" -> if (targets.size > 1) showMultiInfo(targets) else showInfo(targets[0])
                }
            }
            .show()
    }

    // ---------- rename ----------

    private fun showRenameDialog(f: File) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(f.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // select the name without its extension (whole name for folders / dotfiles)
            val dot = f.name.lastIndexOf('.')
            setSelection(0, if (!f.isDirectory && dot > 0) dot else f.name.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Rename " + (if (f.isDirectory) "folder" else "file"))
            .setView(box)
            .setPositiveButton("Rename") { _, _ -> renameEntry(f, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dlg.show()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private fun renameEntry(f: File, raw: String) {
        val name = raw.trim()
        if (name == f.name) return
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
            toast("Invalid name")
            return
        }
        val parent = f.parentFile
        if (parent == null) {
            toast("Can't rename")
            return
        }
        val target = Vfs.child(parent, name)
        // a name that differs only by case is the same file on some filesystems: only block real clashes
        if (target.exists() && !name.equals(f.name, ignoreCase = true)) {
            toast("Already exists: $name")
            return
        }
        if (f.renameTo(target)) {
            toast("Renamed to $name")
        } else {
            toast("Can't rename")
        }
        if (panesReady) {
            active.clearSelection()
            left.load()
            right.load()
        }
    }

    private fun confirmDelete(files: List<File>) {
        val what = if (files.size == 1) "\"${files[0].name}\"" else "${files.size} items"
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete $what permanently? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteFiles(files) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFiles(files: List<File>) {
        if (panesReady) active.clearSelection()
        toast("Deleting…")
        Thread {
            var failed = 0
            for (f in files) if (!FileOps.deleteTree(f)) failed++
            runOnUiThread {
                val ok = files.size - failed
                toast(
                    if (failed == 0) "Deleted $ok " + (if (ok == 1) "item" else "items")
                    else "Deleted $ok, $failed failed"
                )
                if (panesReady) {
                    left.load()
                    right.load()
                }
            }
        }.start()
    }

    /** Combined properties for a multi-selection: item counts, total size, total files / folders inside. */
    private fun showMultiInfo(items: List<File>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dirs = items.count { it.isDirectory && !FileOps.isLink(it) }
        val plain = items.size - dirs
        addRow(box, "Selected", "${items.size} items ($plain files, $dirs folders)")
        val parents = items.map { it.parentFile?.path }.distinct()
        val parent = parents.singleOrNull()
        if (parent != null) {
            val shown = if (parent.endsWith("/")) parent else "$parent/"
            addRow(box, "Parent", shown, true, if (parent.length > 1) parent.trimEnd('/') else parent)
        } else {
            addRow(box, "Parent", "Multiple locations")
        }
        val sizeV = addRow(box, "Total size", "calculating…")
        val fileV = addRow(box, "Total files", "…")
        val folderV = addRow(box, "Total folders", "…")
        addRow(box, "Newest", fmtDateFull(items.maxOf { it.lastModified() }))
        addRow(box, "Oldest", fmtDateFull(items.minOf { it.lastModified() }))

        val dlg = AlertDialog.Builder(this)
            .setTitle("Properties - ${items.size} items")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CLOSE", null)
            .create()
        dlg.show()

        Thread {
            var bytes = 0L
            var files = 0
            var folders = 0
            for (f in items) {
                if (f.isDirectory && !FileOps.isLink(f)) {
                    val st = FileOps.stats(f)
                    bytes += st.bytes
                    files += st.files
                    folders += st.folders + 1 // the selected folder itself counts too
                } else {
                    bytes += f.length()
                    files++
                }
            }
            runOnUiThread {
                if (dlg.isShowing) {
                    sizeV.text = sizeText(bytes)
                    fileV.text = files.toString()
                    folderV.text = folders.toString()
                }
            }
        }.start()
    }

    /** Properties dialog (name, parent, type, size, modified, permissions, owner, group, counts). */
    private fun showInfo(f: File) {
        val isDir = f.isDirectory
        val link = FileOps.isLink(f)
        val st: StructStat? = try {
            Os.lstat(f.path)
        } catch (_: Throwable) {
            null
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        lateinit var dlg: AlertDialog

        addRow(box, "Name", f.name)
        val parentFile = f.parentFile
        if (parentFile != null) {
            val shown = if (parentFile.path.endsWith("/")) parentFile.path else parentFile.path + "/"
            // tap the parent path -> copy it
            addRow(box, "Parent", shown, true, if (parentFile.path.length > 1) parentFile.path.trimEnd('/') else parentFile.path)
        } else {
            addRow(box, "Parent", "-")
        }
        val type = when {
            link -> "Symlink" + (try {
                " -> " + Os.readlink(f.path)
            } catch (_: Throwable) {
                ""
            })
            isDir -> "Folder"
            else -> {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                if (mime != null) "File ($mime)" else "File"
            }
        }
        addRow(box, "Type", type)
        val sizeV = addRow(box, "Size", if (isDir) "calculating…" else sizeText(f.length()))
        addRow(box, "Modified", fmtDateFull(f.lastModified()))
        addRow(box, "Permissions", if (st != null) permString(st.st_mode) else "?")
        addRow(box, "Owner", if (st != null) uidName(st.st_uid) else "?")
        addRow(box, "Group", if (st != null) gidName(st.st_gid) else "?")
        var fileV: TextView? = null
        var folderV: TextView? = null
        if (isDir) {
            fileV = addRow(box, "File Count", "…")
            folderV = addRow(box, "Folder Count", "…")
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("Properties")
            .setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton("MORE", null)
            .setPositiveButton("CLOSE", null)
            .create()
        dlg.show()
        // keep the dialog open when MORE is pressed
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showMore(f, st) }

        if (isDir) {
            Thread {
                val sts = FileOps.stats(f)
                runOnUiThread {
                    if (dlg.isShowing) {
                        sizeV.text = sizeText(sts.bytes)
                        fileV?.text = sts.files.toString()
                        folderV?.text = sts.folders.toString()
                    }
                }
            }.start()
        }
    }

    /** Second page of the properties dialog: paths, times, link info and (for files) checksums. */
    private fun showMore(f: File, st: StructStat?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        addRow(box, "Path", f.path)
        try {
            val canon = f.canonicalPath
            if (canon != f.path) addRow(box, "Real path", canon)
        } catch (_: Throwable) {
        }
        if (FileOps.isLink(f)) {
            try {
                addRow(box, "Link to", Os.readlink(f.path))
            } catch (_: Throwable) {
            }
        }
        if (st != null) {
            addRow(box, "Accessed", fmtDateFull(st.st_atime * 1000))
            addRow(box, "Changed", fmtDateFull(st.st_ctime * 1000))
            addRow(box, "Inode", st.st_ino.toString())
            addRow(box, "Links", st.st_nlink.toString())
        }
        addRow(
            box, "Access",
            (if (f.canRead()) "read" else "no read") + ", " +
                (if (f.canWrite()) "write" else "no write") + ", " +
                (if (f.canExecute()) "execute" else "no execute")
        )
        addRow(box, "Hidden", if (f.name.startsWith(".")) "yes" else "no")

        val stop = AtomicBoolean(false)
        var md5V: TextView? = null
        var sha1V: TextView? = null
        var shaV: TextView? = null
        val hashable = f.isFile && !FileOps.isLink(f)
        if (hashable) {
            md5V = addRow(box, "MD5", "calculating…")
            sha1V = addRow(box, "SHA-1", "calculating…")
            shaV = addRow(box, "SHA-256", "calculating…")
            for (v in listOfNotNull(md5V, sha1V, shaV)) {
                v.textSize = 12f
                v.typeface = Typeface.MONOSPACE
                // tap a checksum -> copy it (only once it has been calculated)
                v.setOnClickListener {
                    val t = v.text.toString()
                    if (t.isNotEmpty() && t != "calculating…" && t != "?") copyText(t)
                }
            }
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("More")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CLOSE", null)
            .create()
        dlg.setOnDismissListener { stop.set(true) }
        dlg.show()

        if (hashable) {
            Thread {
                var r1: String? = null
                var r2: String? = null
                var r3: String? = null
                try {
                    val d1 = MessageDigest.getInstance("MD5")
                    val d2 = MessageDigest.getInstance("SHA-1")
                    val d3 = MessageDigest.getInstance("SHA-256")
                    Vfs.openIn(f).use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (!stop.get()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            d1.update(buf, 0, n)
                            d2.update(buf, 0, n)
                            d3.update(buf, 0, n)
                        }
                    }
                    if (!stop.get()) {
                        r1 = hex(d1.digest())
                        r2 = hex(d2.digest())
                        r3 = hex(d3.digest())
                    }
                } catch (_: Throwable) {
                    r1 = "?"
                    r2 = "?"
                    r3 = "?"
                }
                runOnUiThread {
                    if (dlg.isShowing && r1 != null) {
                        md5V?.text = r1
                        sha1V?.text = r2
                        shaV?.text = r3
                    }
                }
            }.start()
        }
    }

    /**
     * One "label   value" line. Long-press copies the value, or with [tapCopies] a plain tap copies
     * [copyValue] (default: the shown value) and long-press does nothing.
     */
    private fun addRow(
        box: LinearLayout,
        label: String,
        value: String,
        tapCopies: Boolean = false,
        copyValue: String = value,
    ): TextView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val l = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(getColor(R.color.text))
        }
        val v = TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(getColor(R.color.text))
        }
        line.addView(l, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
        line.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (tapCopies) {
            v.setOnClickListener { copyText(copyValue) }
        } else {
            v.setOnLongClickListener {
                copyText(v.text.toString())
                true
            }
        }
        box.addView(line)
        return v
    }

    private fun sizeText(b: Long): String = fmtSize(b) + " (" + String.format(java.util.Locale.US, "%,d", b) + ")"

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(java.util.Locale.US, "%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    /** ls -l style: drwxrws---(2770). */
    private fun permString(mode: Int): String {
        val t = when (mode and 0xF000) {
            0x4000 -> 'd'
            0xA000 -> 'l'
            0x8000 -> '-'
            0x2000 -> 'c'
            0x6000 -> 'b'
            0x1000 -> 'p'
            0xC000 -> 's'
            else -> '?'
        }
        fun tri(shift: Int, special: Boolean, sc: Char): String {
            val r = if ((mode and (4 shl shift)) != 0) 'r' else '-'
            val w = if ((mode and (2 shl shift)) != 0) 'w' else '-'
            val xOn = (mode and (1 shl shift)) != 0
            val x = if (special) (if (xOn) sc else sc.uppercaseChar()) else (if (xOn) 'x' else '-')
            return "" + r + w + x
        }
        return t + tri(6, (mode and 0x800) != 0, 's') +
            tri(3, (mode and 0x400) != 0, 's') +
            tri(0, (mode and 0x200) != 0, 't') +
            String.format(java.util.Locale.US, "(%04o)", mode and 0xFFF)
    }

    private fun uidName(uid: Int): String = when {
        uid == 0 -> "root"
        uid == 1000 -> "system"
        uid == 2000 -> "shell"
        uid == 1023 -> "media_rw"
        uid == 1013 -> "media"
        uid >= 10000 -> {
            val app = uid % 100000
            val user = uid / 100000
            if (app >= 10000) "u${user}_a${app - 10000}" else uid.toString()
        }
        else -> uid.toString()
    }

    private fun gidName(gid: Int): String = when {
        gid == 0 -> "root"
        gid == 1000 -> "system"
        gid == 2000 -> "shell"
        gid == 1023 -> "media_rw"
        gid == 1015 -> "sdcard_rw"
        gid == 1028 -> "sdcard_r"
        gid == 1078 -> "ext_data_rw"
        gid == 1079 -> "ext_obb_rw"
        gid == 9997 -> "everybody"
        gid == 3003 -> "inet"
        gid in 10000..19999 -> "u0_a${gid - 10000}"
        gid in 20000..29999 -> "u0_a${gid - 20000}_cache"
        gid in 50000..59999 -> "all_a${gid - 50000}"
        else -> gid.toString()
    }

    /** Copy to the clipboard. No toast: Android already shows its own "Copied" confirmation. */
    private fun copyText(t: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("lsd", t))
    }

    // ---------- home folder per pane ----------

    private fun homeKey(p: Pane) = if (p === left) "homeLeft" else "homeRight"

    private fun homeOf(isLeft: Boolean): File {
        val saved = getSharedPreferences("lsd", MODE_PRIVATE).getString(if (isLeft) "homeLeft" else "homeRight", null)
        if (saved != null) {
            val f = Vfs.file(saved)
            if (f.isDirectory) return f
        }
        return Environment.getExternalStorageDirectory()
    }

    private fun isHome(p: Pane): Boolean =
        getSharedPreferences("lsd", MODE_PRIVATE).getString(homeKey(p), null) == p.dir.path

    private fun sortKeyPref(p: Pane) = homeKey(p) + "Sort"
    private fun sortRevPref(p: Pane) = homeKey(p) + "SortRev"

    /** Apply the sort order saved with this pane's home (if any); call before the first load. */
    private fun restoreHomeSort(p: Pane) {
        val prefs = getSharedPreferences("lsd", MODE_PRIVATE)
        if (prefs.contains(sortKeyPref(p))) {
            p.restoreSort(prefs.getInt(sortKeyPref(p), FileOps.SORT_NAME), prefs.getBoolean(sortRevPref(p), false))
        }
    }

    /**
     * "Set as Home": the active pane starts in its current folder, with its current sort order,
     * every time the app opens. "Remove home" forgets both.
     */
    private fun toggleHome() {
        if (!panesReady) return
        val p = active
        val side = if (p === left) "Left" else "Right"
        val prefs = getSharedPreferences("lsd", MODE_PRIVATE)
        if (isHome(p)) {
            prefs.edit()
                .remove(homeKey(p))
                .remove(sortKeyPref(p))
                .remove(sortRevPref(p))
                .apply()
            toast("$side pane home removed")
        } else {
            prefs.edit()
                .putString(homeKey(p), p.dir.path)
                .putInt(sortKeyPref(p), p.sortKey)
                .putBoolean(sortRevPref(p), p.sortReverse)
                .apply()
            toast("$side pane home: ${p.dir.path} (sort saved)")
        }
    }

    // ---------- "+" create file / folder ----------

    private fun showCreateDialog() {
        if (!panesReady || active.isArchivePreview) return
        val parent = active.dir
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("New in " + (if (parent.name.isEmpty()) "/" else parent.name))
            .setView(box)
            .setPositiveButton("Create") { _, _ -> createEntry(parent, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dlg.show()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private fun createEntry(parent: File, raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val isDir = text.endsWith("/")
        val rel = text.trim('/')
        val parts = rel.split('/')
        if (rel.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || it.contains('\u0000') }) {
            toast("Invalid name")
            return
        }
        val target = Vfs.child(parent, rel)
        if (target.exists()) {
            toast("Already exists: $rel")
            return
        }
        try {
            if (isDir) {
                if (!target.mkdirs()) throw java.io.IOException("mkdirs failed")
            } else {
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw java.io.IOException("mkdirs failed") }
                if (!target.createNewFile()) throw java.io.IOException("create failed")
            }
            toast((if (isDir) "Folder created: " else "File created: ") + rel)
        } catch (ex: Exception) {
            toast("Can't create: ${ex.message}")
        }
        left.load()
        right.load()
    }

    /** Bottom-right arrows: the other pane jumps to the folder the active pane is in. */
    private fun mirrorToOtherPane() {
        if (!panesReady) return
        val other = if (active === left) right else left
        other.navigate(active.dir)
    }

    // ---------- sort / search / font ----------

    private fun showSortDialog() {
        if (!panesReady) return
        val pane = active
        val names = arrayOf("by Name", "by Size", "by Date", "by Type")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        names.forEachIndexed { i, n ->
            group.addView(RadioButton(this).apply {
                id = RADIO_BASE + i
                text = n
            })
        }
        group.check(RADIO_BASE + pane.sortKey)
        val rev = CheckBox(this).apply {
            text = "Reverse order"
            isChecked = pane.sortReverse
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(group)
            addView(rev)
        }
        AlertDialog.Builder(this)
            .setTitle(if (pane === left) "Sort - Left pane" else "Sort - Right pane")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val k = (group.checkedRadioButtonId - RADIO_BASE).coerceIn(0, 3)
                pane.setSort(k, rev.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchDialog() {
        if (!panesReady) return
        val pane = active
        val input = EditText(this).apply {
            hint = "Name contains…"
            setSingleLine(true)
            setText(pane.searchQuery ?: "")
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Search in " + (if (pane.dir.name.isEmpty()) "/" else pane.dir.name))
            .setView(box)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) pane.clearSearch() else pane.startSearch(q)
            }
            .setNegativeButton("Cancel", null)
        if (pane.searching) b.setNeutralButton("Clear") { _, _ -> pane.clearSearch() }
        val dlg = b.create()
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dlg.show()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun showArchiveFormatDialog(pane: Pane, files: List<File>) {
        val other = if (pane === left) right else left
        if (other.dir is SafFile || files.any { it is SafFile }) {
            toast("Archives can't be created from or into the Termux folder. Use shared storage.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Create archive")
            .setItems(arrayOf("ZIP", "TAR")) { _, which ->
                val destination = if (pane === left) right else left
                showArchiveNameDialog(files, destination.dir, if (which == 0) ArchiveOps.ZIP else ArchiveOps.TAR)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showArchiveNameDialog(files: List<File>, destination: File, format: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText("")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Archive name")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) OpService.startArchive(this, files.map { it.path }, destination.path, name, format)
                else toast("Enter an archive name")
            }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dlg.show()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    // ---------- opening files ----------

    fun openFile(f: File) {
        val ext = f.extension.lowercase()
        if (ext == ArchiveOps.ZIP || ext == ArchiveOps.TAR) {
            if (f is SafFile) {
                toast("Archives in the Termux folder can't be opened here. Copy it to shared storage first.")
                return
            }
            active.openArchive(f)
            return
        }
        if (CodeEditorActivity.supports(f)) {
            startActivity(Intent(this, CodeEditorActivity::class.java).putExtra("path", f.path))
            return
        }
        if (f is SafFile) {
            openTermuxFile(f)
            return
        }
        openWithSystem(f)
    }

    /** Termux files are not readable by other apps directly: hand over a temporary copy. */
    private fun openTermuxFile(f: SafFile) {
        toast("Preparing ${f.name}…")
        Thread {
            val copy: File? = try {
                val dir = File(cacheDir, "open")
                FileOps.deleteTree(dir)
                dir.mkdirs()
                val dst = File(dir, f.name)
                Vfs.openIn(f).use { input ->
                    java.io.FileOutputStream(dst).use { output -> input.copyTo(output, 256 * 1024) }
                }
                dst
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (copy == null) toast("Can't read this file") else openWithSystem(copy)
            }
        }.start()
    }

    private fun openWithSystem(f: File) {
        val ext = f.extension.lowercase()
        try {
            if (ext == "apk" && !packageManager.canRequestPackageInstalls()) {
                toast("Allow installs from lsd, then tap the APK again")
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = if (ext == "apk") "application/vnd.android.package-archive"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            toast("No app can open this file")
        } catch (e: Exception) {
            toast("Can't open: ${e.message}")
        }
    }

    fun showArchiveError(message: String?) = toast("Can't preview archive${message?.let { ": $it" } ?: ""}")

    // ---------- back ----------

    private fun handleBack() {
        if (!panesReady) {
            finish()
            return
        }
        if (active.selected.isNotEmpty()) {
            active.clearSelection()
            return
        }
        if (active.searching) {
            active.clearSearch()
            return
        }
        if (active.dir.path != rootPath && active.goUp()) return
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
