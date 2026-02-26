package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.ui.appgrid.AppAdapter
import com.meowgi.launcher710.util.AppRepository

class SearchOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dialRow: LinearLayout
    private val dialText: TextView
    private val extendedSearchBtn: TextView
    private val resultsRecycler: RecyclerView
    private val adapter: AppAdapter
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    var repository: AppRepository? = null
    var iconResolver: ((LaunchableItem) -> android.graphics.drawable.Drawable)? = null
    var onDismiss: (() -> Unit)? = null
    var onLaunchItem: ((LaunchableItem) -> Unit)? = null

    private var filterItems: List<LaunchableItem>? = null
    private var lastQuery: String = ""
    private var currentResults: List<LaunchableItem> = emptyList()
    private var searchContextLabel: String = "Extended"

    init {
        orientation = VERTICAL
        setBackgroundColor(0xE6000000.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))

        dialRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            setPadding(0, dp(6), 0, dp(2))
        }
        dialText = TextView(context).apply {
            textSize = 14f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
        }
        val phoneIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.sym_action_call)
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        dialRow.addView(dialText)
        dialRow.addView(phoneIcon)
        addView(dialRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        extendedSearchBtn = TextView(context).apply {
            text = "$searchContextLabel search"
            textSize = 13f
            setTextColor(resources.getColor(R.color.bb_text_secondary, null))
            typeface = font
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { performExtendedSearch() }
        }
        addView(extendedSearchBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultsRecycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        adapter = AppAdapter(
            context,
            onClick = { item ->
                val handler = onLaunchItem
                if (handler != null) {
                    handler(item)
                    dismiss()
                } else {
                    when (item) {
                        is LaunchableItem.App -> {
                            repository?.launchApp(item.app)
                            dismiss()
                        }
                        is LaunchableItem.Shortcut, is LaunchableItem.IntentShortcut -> { }
                    }
                }
            },
            onLongClick = { _, _ -> },
            iconResolver = { item -> iconResolver?.invoke(item) ?: item.icon }
        )
        resultsRecycler.adapter = adapter
        resultsRecycler.isClickable = true
        resultsRecycler.setOnClickListener { /* consume so root click doesn't dismiss */ }
        addView(resultsRecycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setOnClickListener { dismiss() }
    }

    /** Set label for the extended search button, e.g. "Frequents", "All Apps", "Extended". */
    fun setSearchContextLabel(label: String) {
        searchContextLabel = label
        extendedSearchBtn.text = "$label search"
    }

    fun show() {
        filterItems = null
        lastQuery = ""
        currentResults = emptyList()
        visibility = VISIBLE
    }

    fun showFilter(items: List<LaunchableItem>) {
        filterItems = items
        lastQuery = ""
        currentResults = emptyList()
        visibility = VISIBLE
        onSearch("")
    }

    fun dismiss() {
        visibility = GONE
        onDismiss?.invoke()
    }

    fun applyQuery(query: String) {
        lastQuery = query
        onSearch(query)
    }

    /** On Enter / Extended search: launch first result if any, otherwise search on web. */
    fun performExtendedSearch() {
        val first = currentResults.firstOrNull()
        if (first != null) {
            val handler = onLaunchItem
            if (handler != null) {
                handler(first)
            } else when (first) {
                is LaunchableItem.App -> repository?.launchApp(first.app)
                else -> {}
            }
            dismiss()
        } else {
            openWebSearchWithQuery(lastQuery)
        }
    }

    fun openWebSearchWithQuery(query: String) {
        if (query.isNotBlank()) {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            dismiss()
        }
    }

    private fun onSearch(query: String) {
        val filter = filterItems
        if (filter != null) {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) filter else filter.filter {
                it.label.toString().lowercase().contains(q)
            }
            currentResults = filtered
            adapter.submitList(filtered)
        } else {
            val repo = repository ?: return
            val results = repo.searchApps(query).map { LaunchableItem.App(it) }
            currentResults = results
            adapter.submitList(results)
        }

        val isPhone = query.matches(Regex("^[0-9+*#]+$"))
        if (isPhone && query.length > 2) {
            dialRow.visibility = VISIBLE
            dialText.text = "${context.getString(R.string.dial_prefix)} $query"
            dialRow.setOnClickListener {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$query"))
                context.startActivity(intent)
                dismiss()
            }
        } else {
            dialRow.visibility = GONE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun applyOpacity(alpha: Int) {
        setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
