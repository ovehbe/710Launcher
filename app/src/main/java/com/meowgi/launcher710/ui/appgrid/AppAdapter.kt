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
import com.meowgi.launcher710.util.LauncherPrefs

class AppAdapter(
    private val context: android.content.Context,
    private var apps: List<AppInfo> = emptyList(),
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit,
    private val viewMode: Int = 0, // 0 = grid, 1 = list
    private val iconResolver: ((AppInfo) -> android.graphics.drawable.Drawable)? = null // Optional icon resolver
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

    fun submitList(list: List<AppInfo>) {
        apps = list
        notifyDataSetChanged()
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
        val app = apps[position]
        val icon = iconResolver?.invoke(app) ?: app.icon
        when (holder) {
            is GridVH -> {
                holder.icon.setImageDrawable(icon)
                holder.icon.layoutParams = holder.icon.layoutParams?.apply {
                    width = iconSizePx
                    height = iconSizePx
                } ?: android.view.ViewGroup.LayoutParams(iconSizePx, iconSizePx)
                holder.label.text = app.label
                holder.itemView.setOnClickListener { onClick(app) }
                holder.itemView.setOnLongClickListener { onLongClick(app, it); true }
            }
            is ListVH -> {
                holder.icon.setImageDrawable(icon)
                holder.label.text = app.label
                // Set accent color on the square
                holder.accentSquare.setBackgroundColor(prefs.accentColor)
                // Set background opacity on the name bar
                val bgAlpha = prefs.listViewBgAlpha
                val bgColor = Color.argb(bgAlpha, 0, 0, 0)
                holder.nameBar.setBackgroundColor(bgColor)
                holder.itemView.setOnClickListener { onClick(app) }
                holder.itemView.setOnLongClickListener { onLongClick(app, it); true }
            }
        }
    }

    override fun getItemCount() = apps.size
}
