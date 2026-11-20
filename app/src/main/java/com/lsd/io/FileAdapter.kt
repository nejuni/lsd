package com.lsd.io

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(private val pane: Pane) : RecyclerView.Adapter<FileAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    private val ctx = pane.rv.context
    private val folderTint = ColorStateList.valueOf(ctx.getColor(R.color.folder))
    private val fileTint = ColorStateList.valueOf(ctx.getColor(R.color.file))
    private val selColor = ctx.getColor(R.color.selected)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = pane.entries.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = pane.entries[position]
        h.name.text = if (e.isParent) ".." else e.displayName
        if (e.isParent) {
            h.sub.visibility = View.GONE
        } else if (pane.isArchivePreview) {
            // Archive preview is name-only; archive contents are never extracted.
            h.sub.visibility = View.GONE
        } else {
            h.sub.visibility = View.VISIBLE
            val base = if (e.isDir) fmtDate(e.modified) else fmtDate(e.modified) + "  " + fmtSize(e.size)
            // in search results also show where the hit lives
            h.sub.text = if (pane.searching) {
                val parent = e.file.parent ?: ""
                val rel = parent.removePrefix(pane.dir.path).trimStart('/')
                (if (rel.isEmpty()) "./" else "$rel/") + "  " + base
            } else {
                base
            }
        }
        h.itemView.setBackgroundColor(if (pane.isSelected(e)) selColor else 0)

        val ext = e.file.extension.lowercase()
        generic(h.icon, if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file, if (e.isDir) folderTint else fileTint)
        if (!pane.isArchivePreview && !e.isDir && e.file !is SafFile && Thumbs.canThumb(ext)) {
            Thumbs.load(h.icon.context, e, ext, h.icon)
        } else {
            h.icon.tag = null
        }

        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) pane.onClick(pane.entries[p], p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) pane.onLongClick(pane.entries[p], p)
            true
        }
    }

    private fun generic(iv: ImageView, res: Int, tint: ColorStateList) {
        val pad = iv.dp(7)
        iv.setPadding(pad, pad, pad, pad)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageResource(res)
        iv.imageTintList = tint
    }
}
