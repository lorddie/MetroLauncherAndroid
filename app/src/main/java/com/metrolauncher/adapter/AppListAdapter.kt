package com.metrolauncher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.metrolauncher.MetroApp
import com.metrolauncher.R
import com.metrolauncher.model.AppInfo
import com.metrolauncher.util.AppLoader

/**
 * Adapter with two row types:
 *  - Header (letter A/B/C...) WP style
 *  - App row (icon + label)
 *
 * Takes a LinkedHashMap "initial -> app list" as input.
 */
class AppListAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
    private val onHeaderClick: () -> Unit,
    private var fontScale: Float = 1f,
    private var accentColor: Int = 0xFF0078D7.toInt()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun setFontScale(scale: Float) {
        if (fontScale == scale) return
        fontScale = scale.coerceIn(0.5f, 2f)
        notifyDataSetChanged()
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        notifyDataSetChanged()
    }

    private sealed class Row {
        data class Header(val letter: String) : Row()
        data class App(val info: AppInfo) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(groups: LinkedHashMap<String, List<AppInfo>>) {
        rows.clear()
        for ((letter, apps) in groups) {
            rows.add(Row.Header(letter))
            apps.forEach { rows.add(Row.App(it)) }
        }
        notifyDataSetChanged()
    }

    fun getPositionForLetter(letter: String): Int {
        return rows.indexOfFirst { it is Row.Header && it.letter == letter }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.App -> TYPE_APP
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_app_header, parent, false))
            else -> AppVH(inflater.inflate(R.layout.item_app_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.letter)
            is Row.App -> (holder as AppVH).bind(row.info)
        }
    }

    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt: TextView = v.findViewById(R.id.header_letter)
        private val baseSize = 26f
        init {
            v.setOnClickListener { onHeaderClick() }
        }
        fun bind(letter: String) {
            txt.text = letter
            txt.textSize = baseSize * fontScale
            txt.setTextColor(accentColor)
            txt.background = null // Remove any previously set background
        }
    }

    private inner class AppVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.app_icon)
        private val label: TextView = v.findViewById(R.id.app_label)
        private val baseSize = 20f
        init {
            v.setOnClickListener {
                val row = rows.getOrNull(bindingAdapterPosition) as? Row.App ?: return@setOnClickListener
                onClick(row.info)
            }
            v.setOnLongClickListener {
                val row = rows.getOrNull(bindingAdapterPosition) as? Row.App ?: return@setOnLongClickListener false
                onLongClick(row.info); true
            }
        }
        fun bind(info: AppInfo) {
            label.text = info.label
            label.textSize = baseSize * fontScale
            val drawable = info.icon ?: AppLoader.loadIcon(icon.context, info.packageName, info.activityName)
            info.icon = drawable
            icon.setImageDrawable(drawable)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }
}
