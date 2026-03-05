package com.meowgi.launcher710.ui.appgrid

import android.graphics.Color
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.util.LauncherPrefs

class AppAdapter(
    private val context: android.content.Context,
    private val onClick: (LaunchableItem) -> Unit,
    private val onLongClick: ((LaunchableItem, View) -> Unit)?,
    private val viewMode: Int = 0, // 0 = grid, 1 = list
    private val iconResolver: ((LaunchableItem) -> android.graphics.drawable.Drawable)? = null,
    private val labelResolver: ((LaunchableItem) -> CharSequence)? = null,
    val isSparseMode: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
        const val VIEW_TYPE_EMPTY = 2

        /** Stable string key used to identify an item across sessions for grid position storage. */
        fun itemKey(item: LaunchableItem): String = when (item) {
            is LaunchableItem.App -> item.app.componentName.flattenToString()
            is LaunchableItem.Shortcut -> item.shortcut.shortcutKey
            is LaunchableItem.IntentShortcut -> item.info.shortcutKey
            is LaunchableItem.LauncherSettings -> "launcher_settings_710"
            is LaunchableItem.Contact -> item.displayName.toString()
            is LaunchableItem.SearchCommand -> "search_cmd_${item.commandName}"
        }
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

    /** Transparent placeholder for an unoccupied grid cell. Cannot be dragged or tapped. */
    class EmptyVH(view: View) : RecyclerView.ViewHolder(view)

    // Backing list; nulls represent empty grid cells in sparse mode.
    private var sparseItems: List<LaunchableItem?> = emptyList()

    private val prefs = LauncherPrefs(context)
    private val iconSizePx: Int
        get() = (prefs.iconSizeDp * context.resources.displayMetrics.density).toInt()

    /** Replace list with a dense (non-sparse) list. */
    fun submitList(list: List<LaunchableItem>) {
        sparseItems = list
        notifyDataSetChanged()
    }

    /** Replace list with a sparse list where nulls represent empty cells. */
    fun submitSparseList(list: List<LaunchableItem?>) {
        sparseItems = list
        notifyDataSetChanged()
    }

    /** Returns only non-null (real) items. */
    fun getCurrentList(): List<LaunchableItem> = sparseItems.filterNotNull()

    /** Returns the full sparse list including null placeholders. */
    fun getSparseList(): List<LaunchableItem?> = sparseItems

    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in sparseItems.indices || to !in sparseItems.indices) return
        val list = sparseItems.toMutableList()
        if (isSparseMode) {
            // Swap: item lands exactly on the target cell, target cell content moves to source.
            val tmp = list[from]
            list[from] = list[to]
            list[to] = tmp
        } else {
            // Dense shift: remove and reinsert (items shuffle around).
            val item = list.removeAt(from)
            list.add(to, item)
        }
        sparseItems = list
        notifyItemMoved(from, to)
    }

    override fun getItemViewType(position: Int): Int {
        if (sparseItems[position] == null) return VIEW_TYPE_EMPTY
        return if (viewMode == 1) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app_list, parent, false)
                ListVH(view)
            }
            VIEW_TYPE_EMPTY -> {
                // Inflate the same grid layout but make it fully invisible so the cell
                // takes up the exact same space as an occupied cell without showing anything.
                // Critically: set the icon dimensions and a non-empty label so the cell
                // measures identically to a real cell — without this, wrap_content with no
                // drawable/text collapses to ~0px and rows of only-empty cells appear as
                // thin slivers, breaking row-height consistency and the maxGridRows measurement.
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app, parent, false)
                view.findViewById<android.widget.ImageView>(R.id.appIcon)?.apply {
                    layoutParams = (layoutParams ?: android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )).also { it.width = iconSizePx; it.height = iconSizePx }
                }
                view.findViewById<android.widget.TextView>(R.id.appLabel)?.text = "\u00A0"
                view.alpha = 0f
                view.isClickable = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                EmptyVH(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app, parent, false)
                GridVH(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is EmptyVH) return
        val item = sparseItems[position] ?: return
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
                holder.itemView.isClickable = true
                holder.itemView.defaultFocusHighlightEnabled = false
                holder.itemView.foreground = prefs.getClickHighlightRipple(context)
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
                holder.itemView.isClickable = true
                holder.itemView.defaultFocusHighlightEnabled = false
                holder.itemView.foreground = prefs.getClickHighlightRipple(context)
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

    override fun getItemCount() = sparseItems.size
}
