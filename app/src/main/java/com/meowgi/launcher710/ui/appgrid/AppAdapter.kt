package com.meowgi.launcher710.ui.appgrid

import android.graphics.Color
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.util.LauncherPrefs

class AppAdapter(
    private val context: android.content.Context,
    private var items: List<LaunchableItem> = emptyList(),
    private val onClick: (LaunchableItem) -> Unit,
    private val onLongClick: ((LaunchableItem, View) -> Unit)?,
    private val viewMode: Int = 0, // 0 = grid, 1 = list
    private val iconResolver: ((LaunchableItem) -> android.graphics.drawable.Drawable)? = null,
    private val labelResolver: ((LaunchableItem) -> CharSequence)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
    }

    class GridVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    class ListVH(view: View) : RecyclerView.ViewHolder(view) {
        val accentSquare: FrameLayout = view.findViewById(R.id.accentSquare)
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val nameBar: LinearLayout = view.findViewById(R.id.nameBar)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    private val prefs = LauncherPrefs(context)
    private val iconSizePx: Int
        get() = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()

    fun submitList(list: List<LaunchableItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun getCurrentList(): List<LaunchableItem> = items

    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
        notifyItemMoved(from, to)
    }

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == 1) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app_list, parent, false)
                ListVH(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app, parent, false)
                GridVH(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val icon = iconResolver?.invoke(item) ?: item.icon
        val label = labelResolver?.invoke(item) ?: item.label
        when (holder) {
            is GridVH -> {
                holder.icon.setImageDrawable(icon)
                holder.icon.layoutParams = holder.icon.layoutParams?.apply {
                    width = iconSizePx
                    height = iconSizePx
                } ?: android.view.ViewGroup.LayoutParams(iconSizePx, iconSizePx)
                holder.label.text = label
                holder.itemView.setOnClickListener { onClick(item) }
                val longClick = onLongClick
                if (longClick != null) {
                    holder.itemView.setOnLongClickListener { longClick(item, it); true }
                } else {
                    holder.itemView.setOnLongClickListener(null)
                }
            }
            is ListVH -> {
                holder.icon.setImageDrawable(icon)
                holder.label.text = label
                val iconBarColor = if (prefs.listViewUseAccent) prefs.accentColor else prefs.listViewCustomColor
                holder.accentSquare.setBackgroundColor(Color.argb(prefs.listViewIconBarAlpha, Color.red(iconBarColor), Color.green(iconBarColor), Color.blue(iconBarColor)))
                val nameBarColor = Color.argb(prefs.listViewNameBarAlpha, 0, 0, 0)
                holder.nameBar.setBackgroundColor(nameBarColor)
                holder.itemView.setOnClickListener { onClick(item) }
                val longClickList = onLongClick
                if (longClickList != null) {
                    holder.itemView.setOnLongClickListener { longClickList(item, it); true }
                } else {
                    holder.itemView.setOnLongClickListener(null)
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
