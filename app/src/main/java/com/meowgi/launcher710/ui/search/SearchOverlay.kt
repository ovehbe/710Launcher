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
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.ui.appgrid.AppAdapter
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.ContactSearchHelper
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.SearchCommandData
import androidx.core.content.ContextCompat

class SearchOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs = LauncherPrefs(context)
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
    /** 0 = always, 1 = disabled, 2 = @ prefix only */
    var contactModeProvider: () -> Int = { 0 }
    var contactSourceProvider: () -> String = { "all" }
    /** When set, provides the icon used for contact results in search (e.g. from Contacts app icon pack or custom). */
    var contactIconProvider: (() -> Drawable)? = null
    /** When set, search enters command mode when query starts with this trigger (e.g. "./"). */
    var commandTriggerProvider: (() -> String?)? = null
    /** When in command mode, provides the list of user-defined commands. */
    var commandsProvider: (() -> List<SearchCommandData>)? = null
    /** Icon shown for command results in search. */
    var commandIconProvider: (() -> Drawable)? = null

    private var filterItems: List<LaunchableItem>? = null
    private var lastQuery: String = ""
    private val defaultContactIcon: Drawable
        get() = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon) ?: android.graphics.drawable.ColorDrawable(Color.GRAY)
    private var currentResults: List<LaunchableItem> = emptyList()
    private var searchContextLabel: String = "Extended"

    init {
        orientation = VERTICAL
        updateBackground()
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

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
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { performExtendedSearch() }
        }
        addView(extendedSearchBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultsRecycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            isFocusable = true
            isFocusableInTouchMode = false
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
                        is LaunchableItem.LauncherSettings -> {
                            context.startActivity(Intent(context, com.meowgi.launcher710.ui.settings.SettingsActivity::class.java))
                            dismiss()
                        }
                        is LaunchableItem.Shortcut, is LaunchableItem.IntentShortcut -> { }
                        is LaunchableItem.SearchCommand -> {
                            when (item.actionType) {
                                0 -> item.actionPackage?.let { pkg ->
                                    context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                                1 -> item.intentUri?.let { uri ->
                                    try {
                                        context.startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) { }
                                }
                            }
                            dismiss()
                        }
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

    /** On Enter / Extended search: launch first result if any, otherwise search on web. In command mode, exact-typed command runs that command. */
    fun performExtendedSearch() {
        val trigger = commandTriggerProvider?.invoke()
        if (!trigger.isNullOrEmpty() && lastQuery.startsWith(trigger)) {
            val commandPart = lastQuery.removePrefix(trigger).trim()
            val commands = commandsProvider?.invoke() ?: emptyList()
            val exact = commands.find { it.name.equals(commandPart, ignoreCase = true) }
            if (exact != null) {
                val icon = commandIconProvider?.invoke() ?: defaultContactIcon
                val item = LaunchableItem.SearchCommand(
                    commandName = exact.name,
                    actionType = exact.actionType,
                    actionPackage = exact.actionPackage,
                    intentUri = exact.intentUri,
                    actionName = exact.actionName,
                    icon = icon
                )
                onLaunchItem?.invoke(item)
                dismiss()
                return
            }
        }
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
                is LaunchableItem.LauncherSettings -> context.startActivity(Intent(context, com.meowgi.launcher710.ui.settings.SettingsActivity::class.java))
                is LaunchableItem.Shortcut, is LaunchableItem.IntentShortcut -> { }
                is LaunchableItem.SearchCommand -> {
                    when (first.actionType) {
                        0 -> first.actionPackage?.let { pkg ->
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        1 -> first.intentUri?.let { uri ->
                            try {
                                context.startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { }
                        }
                    }
                }
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
        val trigger = commandTriggerProvider?.invoke()
        if (!trigger.isNullOrEmpty() && query.startsWith(trigger)) {
            handleCommandSearch(query.removePrefix(trigger))
            return
        }
        val contactMode = contactModeProvider()
        if (contactMode == 2 && query.startsWith("@")) {
            handleAtContactSearch(query.removePrefix("@").trimStart())
            return
        }
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
            val raw = query.trim().lowercase()
            val q = raw.replace(Regex("[^a-z0-9]"), "")
            val settingsItem = repo.createLauncherSettingsItem()
            val settingsMatches = q.isEmpty() && dialDigitsForAppSearch == null ||
                settingsItem.label.toString().lowercase().replace(Regex("[^a-z0-9]"), "").contains(q) ||
                (dialDigitsForAppSearch != null && "710".contains(dialDigitsForAppSearch))
            val baseResults = if (settingsMatches) appResults + settingsItem else appResults
            currentResults = baseResults
            adapter.submitList(baseResults)
            if (contactMode == 0) {
                val queryForContact = query
                val contactIcon = contactIconProvider?.invoke() ?: defaultContactIcon
                ContactSearchHelper.searchAsync(
                    context,
                    queryForContact,
                    dialDigitsForAppSearch,
                    contactIcon,
                    enabled = true,
                    sourceFilter = contactSourceProvider()
                ) { contactResults ->
                    if (lastQuery != queryForContact) return@searchAsync
                    val combined = baseResults + contactResults
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
                    // Encode # as %23 so it is not interpreted as URI fragment (otherwise dialer only receives "*" for *#06#)
                    val telUri = "tel:" + dialDigits.replace("#", "%23")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
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

    private fun handleAtContactSearch(contactQuery: String) {
        dialRow.visibility = GONE
        val contactIcon = contactIconProvider?.invoke() ?: defaultContactIcon
        if (contactQuery.isEmpty()) {
            currentResults = emptyList()
            adapter.submitList(emptyList())
            return
        }
        val snapshotQuery = lastQuery
        val layout = dialerLayoutProvider().coerceIn(0, 1)
        val dialDigits = convertToDialDigits(contactQuery, layout)
        val dialDigitsForContact = dialDigits.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() }
        ContactSearchHelper.searchAsync(
            context,
            contactQuery,
            dialDigitsForContact,
            contactIcon,
            enabled = true,
            sourceFilter = contactSourceProvider()
        ) { contactResults ->
            if (lastQuery != snapshotQuery) return@searchAsync
            currentResults = contactResults
            adapter.submitList(contactResults)
        }
    }

    private fun handleCommandSearch(commandQuery: String) {
        dialRow.visibility = GONE
        val commands = commandsProvider?.invoke() ?: emptyList()
        val icon = commandIconProvider?.invoke() ?: defaultContactIcon
        val raw = commandQuery.trim().lowercase()
        val q = raw.replace(Regex("[^a-z0-9]"), "")
        val filtered = if (q.isEmpty()) {
            commands
        } else {
            commands.filter { cmd ->
                cmd.name.lowercase().replace(Regex("[^a-z0-9]"), "").contains(q)
            }
        }
        val items = filtered.map { cmd ->
            LaunchableItem.SearchCommand(
                commandName = cmd.name,
                actionType = cmd.actionType,
                actionPackage = cmd.actionPackage,
                intentUri = cmd.intentUri,
                actionName = cmd.actionName,
                icon = icon
            )
        }
        currentResults = items
        adapter.submitList(items)
    }

    /**
     * Converts search query to dial digits using the selected layout.
     * Layout 0 = Qwerty: w→1, e→2, r→3, s→4, d→5, f→6, z→7, x→8, c→9; a→*, q→#, o→+; digits and +*# pass through.
     * Layout 1 = T9: abc→2, def→3, ghi→4, jkl→5, mno→6, pqrs→7, tuv→8, wxyz→9; 0/space→0, . or 1→1; digits and +*# pass through.
     */
    private fun convertToDialDigits(query: String, layout: Int): String {
        if (query.isEmpty()) return ""
        // If the user has typed a space, treat the query as normal text only
        // and stop generating dial digits / dialer suggestions.
        if (query.any { it.isWhitespace() }) return ""
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
            else -> { // 0 = Qwerty (a→*, q→#, o→+; w/e/r/s/d/f/z/x/c→1–9)
                for (ch in query) {
                    when (ch) {
                        '+', '*', '#' -> sb.append(ch)
                        in '0'..'9' -> sb.append(ch)
                        in "aA" -> sb.append('*')
                        in "qQ" -> sb.append('#')
                        in "oO" -> sb.append('+')
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
        updateBackground(alpha)
    }

    private fun updateBackground(alpha: Int = prefs.searchOverlayAlpha) {
        val color = if (prefs.searchOverlayUseDefaultBackground) {
            Color.argb(alpha, 0, 0, 0)
        } else {
            val c = prefs.searchOverlayCustomBackgroundColor
            Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
        }
        setBackgroundColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
