package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.ui.appgrid.AppAdapter
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.ContactSearchHelper
import androidx.core.content.ContextCompat

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
    var onItemLongClick: ((LaunchableItem, View) -> Unit)? = null
    /** 0 = Qwerty (BlackBerry Bold style), 1 = T9. Used to convert letters to dial digits. */
    var dialerLayoutProvider: () -> Int = { 0 }
    var contactSearchEnabledProvider: () -> Boolean = { true }
    var contactSourceProvider: () -> String = { "all" }

    private var filterItems: List<LaunchableItem>? = null
    private var lastQuery: String = ""
    private val defaultContactIcon: Drawable
        get() = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon) ?: android.graphics.drawable.ColorDrawable(Color.GRAY)
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
                        is LaunchableItem.Contact -> {
                            item.phoneNumbers.firstOrNull()?.let { num ->
                                try {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                    dismiss()
                                } catch (_: Exception) {
                                    Toast.makeText(context, R.string.dial_no_app, Toast.LENGTH_SHORT).show()
                                }
                            } ?: dismiss()
                        }
                        is LaunchableItem.Shortcut, is LaunchableItem.IntentShortcut -> { }
                    }
                }
            },
            onLongClick = { item, view -> onItemLongClick?.invoke(item, view) },
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
                is LaunchableItem.Contact -> first.phoneNumbers.firstOrNull()?.let { num ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) { Toast.makeText(context, R.string.dial_no_app, Toast.LENGTH_SHORT).show() }
                }
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
        val layout = dialerLayoutProvider().coerceIn(0, 1)
        val dialDigits = convertToDialDigits(query, layout)
        val dialDigitsForAppSearch = dialDigits.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() }

        val filter = filterItems
        if (filter != null) {
            val raw = query.trim().lowercase()
            val q = raw.replace(Regex("[^a-z0-9]"), "")
            val filtered = if (q.isEmpty() && dialDigitsForAppSearch == null) filter else filter.filter {
                val normalized = it.label.toString().lowercase().replace(Regex("[^a-z0-9]"), "")
                normalized.contains(q) || (dialDigitsForAppSearch != null && normalized.contains(dialDigitsForAppSearch))
            }
            currentResults = filtered
            adapter.submitList(filtered)
        } else {
            val repo = repository ?: return
            val appResults = repo.searchApps(query, dialDigitsForAppSearch).map { LaunchableItem.App(it) }
            currentResults = appResults
            adapter.submitList(appResults)
            if (contactSearchEnabledProvider()) {
                val queryForContact = query
                ContactSearchHelper.searchAsync(
                    context,
                    queryForContact,
                    dialDigitsForAppSearch,
                    defaultContactIcon,
                    enabled = contactSearchEnabledProvider(),
                    sourceFilter = contactSourceProvider()
                ) { contactResults ->
                    if (lastQuery != queryForContact) return@searchAsync
                    val combined = appResults + contactResults
                    currentResults = combined
                    adapter.submitList(combined)
                }
            }
        }
        if (dialDigits.isNotEmpty()) {
            dialRow.visibility = VISIBLE
            dialText.text = "${context.getString(R.string.dial_prefix)} $dialDigits"
            dialRow.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialDigits")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    dismiss()
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.dial_no_app, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            dialRow.visibility = GONE
        }
    }

    /**
     * Converts search query to dial digits using the selected layout.
     * Layout 0 = Qwerty (w→1, e→2, r→3, s→4, d→5, f→6, z→7, x→8, c→9; 0→0; digits and +*# pass through).
     * Layout 1 = T9 (abc→2, def→3, ghi→4, jkl→5, mno→6, pqrs→7, tuv→8, wxyz→9; 0/space→0, . or 1→1; digits and +*# pass through).
     */
    private fun convertToDialDigits(query: String, layout: Int): String {
        if (query.isEmpty()) return ""
        val sb = StringBuilder()
        when (layout) {
            1 -> { // T9
                for (ch in query) {
                    when (ch) {
                        '+', '*', '#' -> sb.append(ch)
                        in '0'..'9' -> sb.append(ch)
                        ' ', '\u00A0' -> sb.append('0')
                        '.' -> sb.append('1')
                        in 'a'..'z' -> sb.append(t9LetterToDigit(ch))
                        in 'A'..'Z' -> sb.append(t9LetterToDigit(ch.lowercaseChar()))
                        else -> { /* skip */ }
                    }
                }
            }
            else -> { // 0 = Qwerty
                for (ch in query) {
                    when (ch) {
                        '+', '*', '#' -> sb.append(ch)
                        in '0'..'9' -> sb.append(ch)
                        in "wWeErRsSdDfFzZxXcC" -> sb.append(qwertyLetterToDigit(ch.lowercaseChar()))
                        else -> { /* skip unmapped letters */ }
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun qwertyLetterToDigit(c: Char): Char = when (c) {
        'w' -> '1'; 'e' -> '2'; 'r' -> '3'; 's' -> '4'; 'd' -> '5'
        'f' -> '6'; 'z' -> '7'; 'x' -> '8'; 'c' -> '9'
        else -> '0'
    }

    private fun t9LetterToDigit(c: Char): Char = when (c) {
        in "abc" -> '2'; in "def" -> '3'; in "ghi" -> '4'; in "jkl" -> '5'
        in "mno" -> '6'; in "pqrs" -> '7'; in "tuv" -> '8'; in "wxyz" -> '9'
        else -> '0'
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
