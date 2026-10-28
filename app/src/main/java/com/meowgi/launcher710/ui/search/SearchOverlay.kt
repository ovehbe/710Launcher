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
import com.meowgi.launcher710.util.SearchNormalizer
import androidx.core.content.ContextCompat

class SearchOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private companion object {
        val NON_COMMAND_CHARS = Regex("[^a-z0-9]")
    }

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
    /** Called when user taps "Refresh Cache" in search results. */
    var onRefreshCache: (() -> Unit)? = null

    /** A searchable item with its label pre-normalized once per session, not per keystroke. */
    private class IndexedItem(
        val item: LaunchableItem,
        val normalizedLabel: String,
        val words: List<String>,
        val normalizedPackage: String,
        val aliases: Array<out String> = emptyArray()
    ) {
        companion object {
            fun of(item: LaunchableItem, vararg aliases: String): IndexedItem {
                val app = (item as? LaunchableItem.App)?.app
                return if (app != null) {
                    IndexedItem(item, app.normalizedLabel, app.normalizedWords, app.normalizedPackage, aliases)
                } else {
                    val label = item.label.toString()
                    IndexedItem(item, SearchNormalizer.normalize(label), SearchNormalizer.words(label), "", aliases)
                }
            }
        }
    }

    private var filterIndex: List<IndexedItem>? = null
    private var lastQuery: String = ""
    /** Query the visible results belong to; null forces the next [applyQuery] to run. */
    private var appliedQuery: String? = null
    private var currentResults: List<LaunchableItem> = emptyList()
    private var searchContextLabel: String = "Extended"
    private var currentDialDigits = ""

    // Per-session caches, cleared in show()/showFilter(). Building these touches icon packs,
    // bitmap shaping and PackageManager, which used to happen on every keystroke.
    private var builtInIndex: List<IndexedItem>? = null
    private var cachedContactIcon: Drawable? = null
    private var cachedCommandIcon: Drawable? = null

    private val defaultContactIcon: Drawable by lazy {
        ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon) ?: android.graphics.drawable.ColorDrawable(Color.GRAY)
    }

    private fun contactIcon(): Drawable =
        cachedContactIcon ?: (contactIconProvider?.invoke() ?: defaultContactIcon).also { cachedContactIcon = it }

    private fun commandIcon(): Drawable =
        cachedCommandIcon ?: (commandIconProvider?.invoke() ?: defaultContactIcon).also { cachedCommandIcon = it }

    private fun builtIns(repo: AppRepository): List<IndexedItem> =
        builtInIndex ?: listOf(
            IndexedItem.of(repo.createLauncherSettingsItem(), "710"),
            IndexedItem.of(repo.createRefreshCacheItem())
        ).also { builtInIndex = it }

    private fun resetSession() {
        lastQuery = ""
        appliedQuery = null
        currentResults = emptyList()
        shownContacts = emptyList()
        builtInIndex = null
        cachedContactIcon = null
        cachedCommandIcon = null
        ContactSearchHelper.invalidateCache()
    }

    private fun publish(results: List<LaunchableItem>) {
        currentResults = results
        adapter.submitListDiffed(results)
    }

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
        dialRow.setOnClickListener {
            try {
                // Encode # as %23 so it is not interpreted as URI fragment (otherwise dialer only receives "*" for *#06#)
                val telUri = "tel:" + currentDialDigits.replace("#", "%23")
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                dismiss()
            } catch (_: Exception) {
                Toast.makeText(context, R.string.dial_no_app, Toast.LENGTH_SHORT).show()
            }
        }
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
            // Its size comes from the weight, not the content, so result changes must not
            // request a layout of the whole activity on every keystroke.
            setHasFixedSize(true)
            // Results should snap to the new query, not fade/slide over a few frames.
            itemAnimator = null
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
                        is LaunchableItem.RefreshCache -> {
                            onRefreshCache?.invoke()
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
        filterIndex = null
        resetSession()
        visibility = VISIBLE
    }

    fun showFilter(items: List<LaunchableItem>) {
        filterIndex = items.map { IndexedItem.of(it) }
        resetSession()
        visibility = VISIBLE
        applyQuery("")
    }

    fun dismiss() {
        ContactSearchHelper.cancel()
        visibility = GONE
        onDismiss?.invoke()
    }

    /** Synchronous and cheap enough to run on every keystroke; a repeat of the shown query is a no-op. */
    fun applyQuery(query: String) {
        if (query == appliedQuery) return
        lastQuery = query
        appliedQuery = query
        android.os.Trace.beginSection("SearchOverlay.applyQuery")
        try {
            onSearch(query)
        } finally {
            android.os.Trace.endSection()
        }
    }

    /** On Enter / Extended search: launch first result if any, otherwise search on web. In command mode, exact-typed command runs that command. */
    fun performExtendedSearch() {
        val trigger = commandTriggerProvider?.invoke()
        if (!trigger.isNullOrEmpty() && lastQuery.startsWith(trigger)) {
            val commandPart = lastQuery.removePrefix(trigger).trim()
            val commands = commandsProvider?.invoke() ?: emptyList()
            val exact = commands.find { it.name.equals(commandPart, ignoreCase = true) }
            if (exact != null) {
                val icon = commandIcon()
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
                is LaunchableItem.RefreshCache -> onRefreshCache?.invoke()
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
            // NEW_TASK keeps the search app out of the launcher's own task, because the home task
            // is never listed in Recents. CLEAR_TASK then finishes whatever task a previous
            // fallback left behind, so the search app is recreated with this query rather than the
            // stale task being brought forward with its old results.
            val searchLaunchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(searchLaunchFlags)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(searchLaunchFlags))
            }
            dismiss()
        }
    }

    /** Contact rows currently shown, carried into the next keystroke while its own lookup runs. */
    private var shownContacts: List<LaunchableItem.Contact> = emptyList()

    private fun onSearch(query: String) {
        val trigger = commandTriggerProvider?.invoke()
        if (!trigger.isNullOrEmpty() && query.startsWith(trigger)) {
            ContactSearchHelper.cancel()
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
        val nq = SearchNormalizer.normalize(query)

        val filter = filterIndex
        if (filter != null) {
            ContactSearchHelper.cancel()
            val filtered = if (nq.isEmpty() && dialDigitsForAppSearch == null) {
                filter.map { it.item }
            } else {
                val nameMatches = mutableListOf<LaunchableItem>()
                val packageOnlyMatches = mutableListOf<LaunchableItem>()
                for (entry in filter) {
                    when (SearchNormalizer.match(nq, dialDigitsForAppSearch, entry.normalizedLabel, entry.words, entry.normalizedPackage)) {
                        SearchNormalizer.NAME_MATCH -> nameMatches += entry.item
                        SearchNormalizer.PACKAGE_MATCH -> packageOnlyMatches += entry.item
                    }
                }
                nameMatches + packageOnlyMatches
            }
            publish(filtered)
        } else {
            val repo = repository ?: return
            val appResults = repo.searchApps(query, dialDigitsForAppSearch).map { LaunchableItem.App(it) }
            val builtInItems = builtIns(repo).filter { matchesBuiltIn(it, nq, dialDigitsForAppSearch) }.map { it.item }
            val baseResults = appResults + builtInItems
            if (contactMode == 0) {
                // Typing usually narrows the query, so the previous contacts that still match are
                // shown right away instead of blinking out until this keystroke's lookup returns.
                val carried = shownContacts.filter { contactStillMatches(it, nq, dialDigitsForAppSearch) }
                shownContacts = carried
                publish(baseResults + carried)
                val queryForContact = query
                ContactSearchHelper.searchAsync(
                    context,
                    queryForContact,
                    dialDigitsForAppSearch,
                    contactIcon(),
                    enabled = true,
                    sourceFilter = contactSourceProvider()
                ) { contactResults ->
                    if (lastQuery != queryForContact) return@searchAsync
                    shownContacts = contactResults
                    publish(baseResults + contactResults)
                }
            } else {
                ContactSearchHelper.cancel()
                shownContacts = emptyList()
                publish(baseResults)
            }
        }
        updateDialRow(dialDigits)
    }

    private fun updateDialRow(dialDigits: String) {
        if (dialDigits.isEmpty()) {
            dialRow.visibility = GONE
            return
        }
        dialRow.visibility = VISIBLE
        // setText always requests a layout, so skip it when the digits didn't change.
        if (dialDigits != currentDialDigits) {
            currentDialDigits = dialDigits
            dialText.text = "${context.getString(R.string.dial_prefix)} $dialDigits"
        }
    }

    private fun matchesBuiltIn(entry: IndexedItem, nq: String, digits: String?): Boolean {
        if (nq.isEmpty() && digits == null) return true
        if (nq.isNotEmpty() && entry.normalizedLabel.contains(nq)) return true
        if (nq.isNotEmpty() && SearchNormalizer.matchesPrefixPerWord(nq, entry.words)) return true
        for (alias in entry.aliases) {
            if (nq.isNotEmpty() && alias.contains(nq)) return true
            if (digits != null && alias.contains(digits)) return true
        }
        return digits != null && entry.normalizedLabel.contains(digits)
    }

    private fun contactStillMatches(contact: LaunchableItem.Contact, nq: String, digits: String?): Boolean {
        if (nq.isNotEmpty() && SearchNormalizer.normalize(contact.displayName.toString()).startsWith(nq)) return true
        return digits != null && contact.phoneNumbers.any { number -> number.filter { it in '0'..'9' }.contains(digits) }
    }

    private fun handleAtContactSearch(contactQuery: String) {
        dialRow.visibility = GONE
        if (contactQuery.isEmpty()) {
            ContactSearchHelper.cancel()
            shownContacts = emptyList()
            publish(emptyList())
            return
        }
        val snapshotQuery = lastQuery
        val layout = dialerLayoutProvider().coerceIn(0, 1)
        val dialDigits = convertToDialDigits(contactQuery, layout)
        val dialDigitsForContact = dialDigits.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() }
        val carried = shownContacts.filter {
            contactStillMatches(it, SearchNormalizer.normalize(contactQuery), dialDigitsForContact)
        }
        shownContacts = carried
        publish(carried)
        ContactSearchHelper.searchAsync(
            context,
            contactQuery,
            dialDigitsForContact,
            contactIcon(),
            enabled = true,
            sourceFilter = contactSourceProvider()
        ) { contactResults ->
            if (lastQuery != snapshotQuery) return@searchAsync
            shownContacts = contactResults
            publish(contactResults)
        }
    }

    private fun handleCommandSearch(commandQuery: String) {
        dialRow.visibility = GONE
        shownContacts = emptyList()
        val commands = commandsProvider?.invoke() ?: emptyList()
        val icon = commandIcon()
        val q = NON_COMMAND_CHARS.replace(commandQuery.trim().lowercase(), "")
        val filtered = if (q.isEmpty()) {
            commands
        } else {
            commands.filter { cmd ->
                NON_COMMAND_CHARS.replace(cmd.name.lowercase(), "").contains(q)
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
        publish(items)
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
