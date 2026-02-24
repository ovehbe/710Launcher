package com.meowgi.launcher710.ui.appgrid

import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo

class AppAdapter(
    private val context: android.content.Context,
    private var apps: List<AppInfo> = emptyList(),
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    private val iconSizePx: Int
        get() = (com.meowgi.launcher710.util.LauncherPrefs(context).iconSizeDp * context.resources.displayMetrics.density).toInt()

    fun submitList(list: List<AppInfo>) {
        apps = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.icon.layoutParams = holder.icon.layoutParams?.apply {
            width = iconSizePx
            height = iconSizePx
        } ?: android.view.ViewGroup.LayoutParams(iconSizePx, iconSizePx)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener { onLongClick(app, it); true }
    }

    override fun getItemCount() = apps.size
}
