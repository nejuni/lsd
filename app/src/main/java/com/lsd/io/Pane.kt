package com.lsd.io

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** One side of the dual-pane view: current folder, history, selection, sort and search. */
class Pane(
    private val act: MainActivity,
    val rv: RecyclerView,
    val indicator: View,
    start: File,
) {
    private data class LocationState(val dir: File, val archive: ArchiveLocation?)

    var dir: File = start
        private set
    private var archiveLocation: ArchiveLocation? = null
    val isArchivePreview: Boolean get() = archiveLocation != null
    val displayPath: String get() = archiveLocation?.displayPath ?: dir.path
    var entries: List<Entry> = emptyList()
        private set
    var folders = 0
        private set
    var files = 0
        private set
    var archiveTruncated = false
        private set
    val selected = LinkedHashSet<String>()

    /** Sort order for this pane (see FileOps.SORT_*). */
    var sortKey = FileOps.SORT_NAME
        private set
    var sortReverse = false
        private set

    /** Non-null while the pane shows recursive search results for [dir]. */
    var searchQuery: String? = null
        private set
    val searching: Boolean get() = searchQuery != null

    private val adapter = FileAdapter(this)
    private val back = ArrayList<LocationState>()
    private val fwd = ArrayList<LocationState>()
    private var gen = 0

    init {
        rv.layoutManager = LinearLayoutManager(rv.context)
        rv.adapter = adapter
        rv.itemAnimator = null
        // Right swipe on a row -> Cut / Copy / Delete / Info menu.
        // Left swipe on a row  -> file / folder information (combined info for a multi-selection).
        // Pure gesture detection: the row never moves, there is no animation.
        val swipeDist = (40 * rv.resources.displayMetrics.density).toInt()
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var downPos = RecyclerView.NO_POSITION

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        act.setActive(this@Pane)
                        downX = e.x
                        downY = e.y
                        val child = rv.findChildViewUnder(e.x, e.y)
                        downPos = if (child != null) rv.getChildAdapterPosition(child) else RecyclerView.NO_POSITION
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pos = downPos
                        if (pos != RecyclerView.NO_POSITION && pos < entries.size) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (Math.abs(dx) >= swipeDist && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                downPos = RecyclerView.NO_POSITION
                                val entry = entries[pos]
                                if (!entry.isParent && !isArchivePreview) {
                                    if (dx > 0) act.showItemMenu(this@Pane, entry)
                                    else act.showSwipeInfo(this@Pane, entry)
                                    return true // swallow the rest of this touch
                                }
                            }
                        }
                    }
                }
                return false
            }
        })
    }

    fun load(resetScroll: Boolean = false) {
        val d = dir
        val g = ++gen
        val hidden = act.showHidden
        val sk = sortKey
        val sr = sortReverse
        val q = searchQuery
        val archive = archiveLocation
        val executor = if (archive != null) Bg.archiveIo else Bg.io
        executor.execute {
            val l = try {
                if (archive != null) ArchiveOps.list(
                    archive, hidden, sk, sr, q,
                    cancelled = { g != gen },
                    onPartial = { partial ->
                        Bg.main.post {
                            if (g == gen) applyListing(partial, false)
                        }
                    },
                )
                else if (q == null) FileOps.list(d, hidden, sk, sr)
                else FileOps.search(d, q, hidden, sk, sr) { g != gen }
            } catch (e: Throwable) {
                if (archive != null) Bg.main.post { if (g == gen) act.showArchiveError(e.message) }
                Listing(emptyList(), 0, 0)
            }
            Bg.main.post {
                if (g != gen) return@post
                applyListing(l, resetScroll)
            }
        }
    }

    private fun applyListing(listing: Listing, resetScroll: Boolean) {
        entries = listing.items
        folders = listing.folders
        files = listing.files
        archiveTruncated = isArchivePreview && listing.truncated
        val present = listing.items.map { it.file.path }.toSet()
        selected.retainAll(present)
        adapter.notifyDataSetChanged()
        if (resetScroll) rv.scrollToPosition(0)
        act.onPaneChanged(this)
    }

    // ---------- sort / search / look ----------

    fun setSort(key: Int, reverse: Boolean) {
        sortKey = key
        sortReverse = reverse
        load()
    }

    /** Set the sort order without reloading (used at start-up, before the first load). */
    fun restoreSort(key: Int, reverse: Boolean) {
        sortKey = key.coerceIn(FileOps.SORT_NAME, FileOps.SORT_TYPE)
        sortReverse = reverse
    }

    fun startSearch(query: String) {
        searchQuery = query
        selected.clear()
        load(true)
        act.onSelectionChanged()
    }

    fun clearSearch() {
        if (searchQuery == null) return
        searchQuery = null
        selected.clear()
        load(true)
        act.onSelectionChanged()
    }

    /** Re-draw rows after a font size change. */
    fun refreshLook() {
        adapter.notifyDataSetChanged()
    }

    // ---------- navigation ----------

    fun navigate(target: File) {
        searchQuery = null
        if (archiveLocation != null || target.path != dir.path) {
            back.add(locationState())
            fwd.clear()
            dir = target
            archiveLocation = null
            selected.clear()
        }
        load(true)
        act.onSelectionChanged()
    }

    fun openArchive(archive: File) {
        back.add(locationState())
        fwd.clear()
        archiveLocation = ArchiveLocation(archive)
        searchQuery = null
        selected.clear()
        entries = emptyList()
        folders = 0
        files = 0
        archiveTruncated = false
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(0)
        load(true)
        act.onSelectionChanged()
    }

    private fun navigateArchive(member: String) {
        val current = archiveLocation ?: return
        val target = ArchiveOps.childLocation(current, member)
        if (target.prefix != current.prefix) {
            back.add(locationState())
            fwd.clear()
            archiveLocation = target
            searchQuery = null
            selected.clear()
            entries = emptyList()
            folders = 0
            files = 0
            archiveTruncated = false
            adapter.notifyDataSetChanged()
            rv.scrollToPosition(0)
        }
        load(true)
        act.onSelectionChanged()
    }

    private fun locationState() = LocationState(dir, archiveLocation)

    private fun restore(state: LocationState) {
        dir = state.dir
        archiveLocation = state.archive
    }

    fun goBack() {
        if (searching) {
            clearSearch()
            return
        }
        if (back.isEmpty()) return
        fwd.add(locationState())
        restore(back.removeAt(back.size - 1))
        selected.clear()
        load(true)
        act.onSelectionChanged()
    }

    fun goForward() {
        if (fwd.isEmpty()) return
        back.add(locationState())
        restore(fwd.removeAt(fwd.size - 1))
        searchQuery = null
        selected.clear()
        load(true)
        act.onSelectionChanged()
    }

    fun goUp(): Boolean {
        if (searching) {
            clearSearch()
            return true
        }
        val archive = archiveLocation
        if (archive != null) {
            if (archive.prefix.isEmpty()) {
                back.add(locationState())
                fwd.clear()
                archiveLocation = null
                selected.clear()
                load(true)
                act.onSelectionChanged()
            } else {
                val parent = archive.prefix.trimEnd('/').substringBeforeLast('/', "")
                navigateArchive(if (parent.isEmpty()) "" else "$parent/")
            }
            return true
        }
        val p = dir.parentFile ?: return false
        navigate(p)
        return true
    }

    // ---------- selection ----------

    fun isSelected(e: Entry) = selected.contains(e.file.path)

    fun onClick(e: Entry, pos: Int) {
        act.setActive(this)
        if (archiveLocation != null) {
            when {
                e.isParent -> goUp()
                e.isDir -> e.archiveMember?.let { navigateArchive(it) }
            }
            return
        }
        when {
            e.isParent -> navigate(e.file)
            selected.isNotEmpty() -> toggle(e, pos)
            e.isDir -> navigate(e.file)
            else -> act.openFile(e.file)
        }
    }

    fun onLongClick(e: Entry, pos: Int) {
        act.setActive(this)
        if (!isArchivePreview && !e.isParent) toggle(e, pos)
    }

    private fun toggle(e: Entry, pos: Int) {
        if (!selected.remove(e.file.path)) selected.add(e.file.path)
        adapter.notifyItemChanged(pos)
        act.onSelectionChanged()
    }

    fun selectAll() {
        if (isArchivePreview) return
        entries.filter { !it.isParent }.forEach { selected.add(it.file.path) }
        adapter.notifyDataSetChanged()
        act.onSelectionChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        adapter.notifyDataSetChanged()
        act.onSelectionChanged()
    }

    fun selectedFiles(): List<File> = selected.map { Vfs.file(it) }
}
