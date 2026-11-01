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
import com.meowgi.launcher710.ai.AiAnswerSession
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.ai.AiMarkdown
import com.meowgi.launcher710.ai.AiSecrets
import com.meowgi.launcher710.ai.AiSettings
import com.meowgi.launcher710.ai.AiState
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.ui.appgrid.AppAdapter
import com.meowgi.launcher710.ui.settings.SettingsActivity
import com.meowgi.launcher710.ui.settings.SettingsScreen
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.ContactSearchHelper
import com.meowgi.launcher710.util.DialNumber
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.SearchCommandData
import com.meowgi.launcher710.util.SearchNormalizer
import com.meowgi.launcher710.util.SearchRanker
import com.meowgi.launcher710.util.WebSuggestions
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
    private val suggestionsBox: LinearLayout
    private val resultsRecycler: RecyclerView
    private val aiPanel: ScrollView
    private val aiProgress: ProgressBar
    private val aiText: TextView
    private val aiError: TextView
    private val aiSettingsLink: TextView
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

    /** Read once per session so the keystroke path doesn't touch prefs. */
    private var webSuggestionsEnabled = false
    /** False in command and @-contact modes, where web suggestions make no sense. */
    private var webSuggestionsAllowed = false
    internal val webSuggestions = WebSuggestions { query, suggestions -> showWebSuggestions(query, suggestions) }
    internal val aiSession = AiAnswerSession { renderAi(it) }
    /** Read on each AI trigger, so settings changes apply without reopening search. */
    internal var aiConfigProvider: () -> AiConfigResult = { AiSettings.load(prefs, AiSecrets(context)) }
    private val markdownStyle by lazy {
        AiMarkdown.Style(
            codeBackground = 0x33FFFFFF,
            quoteColor = resources.getColor(R.color.bb_text_secondary, null),
            indentPx = dp(14)
        )
    }
    /** Number the current query dials (dial row, Enter), or null; set before results are published. */
    private var queryDialNumber: String? = null
    /** Normalized current query, for telling text matches from keypad-digit matches. */
    private var queryNormalized = ""

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
        queryDialNumber = null
        queryNormalized = ""
        appliedQuery = null
        currentResults = emptyList()
        shownContacts = emptyList()
        builtInIndex = null
        cachedContactIcon = null
        cachedCommandIcon = null
        ContactSearchHelper.invalidateCache()
        webSuggestionsEnabled = prefs.searchWebSuggestions
        aiSession.cancel()
        clearWebSuggestions()
        clearNavSelection()
        // The overlay is only hidden between sessions, so its lists keep their scroll position.
        resultsRecycler.scrollToPosition(0)
        aiPanel.scrollTo(0, 0)
        if (webSuggestionsEnabled) webSuggestions.prewarm()
    }

    private fun publish(results: List<LaunchableItem>) {
        currentResults = results
        clearNavSelection()
        adapter.submitListDiffed(results)
        updateWebSuggestions(results)
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
        dialRow.setOnClickListener { dial(currentDialDigits) }
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

        suggestionsBox = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        addView(suggestionsBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
            onClick = { item -> openResult(item) },
            onLongClick = { item, view -> onItemLongClick?.invoke(item, view) },
            iconResolver = { item -> iconResolver?.invoke(item) ?: item.icon }
        )
        resultsRecycler.adapter = adapter
        resultsRecycler.isClickable = true
        resultsRecycler.setOnClickListener { /* consume so root click doesn't dismiss */ }
        addView(resultsRecycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        aiProgress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(prefs.accentColor)
        }
        aiText = TextView(context).apply {
            textSize = 15f
            setTextColor(resources.getColor(R.color.bb_text_primary, null))
            typeface = font
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        aiError = TextView(context).apply {
            textSize = 14f
            setTextColor(resources.getColor(R.color.bb_text_secondary, null))
            typeface = font
        }
        aiSettingsLink = TextView(context).apply {
            text = context.getString(R.string.ai_open_settings)
            textSize = 14f
            setTextColor(prefs.accentColor)
            typeface = font
            setPadding(0, dp(10), 0, dp(10))
            isFocusable = true
            setOnClickListener {
                context.startActivity(SettingsActivity.intent(context, SettingsScreen.AI))
                dismiss()
            }
        }
        val aiContent = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
            addView(aiProgress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
            addView(aiText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(aiError, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(aiSettingsLink, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        aiPanel = ScrollView(context).apply {
            visibility = GONE
            isFocusable = true
            isFocusableInTouchMode = false
            isFillViewport = true
            setOnClickListener { /* consume so root click doesn't dismiss */ }
            addView(aiContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(aiPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setOnClickListener { dismiss() }

        resultsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (navPos != null) invalidate()
            }
        })
        aiPanel.setOnScrollChangeListener { _, _, _, _, _ -> if (navPos != null) invalidate() }
    }

    private fun openResult(item: LaunchableItem) {
        val handler = onLaunchItem
        if (handler != null) {
            handler(item)
            dismiss()
            return
        }
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
                context.startActivity(com.meowgi.launcher710.ui.settings.SettingsActivity.intent(context))
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

    /** Something the trackpad highlight can rest on. */
    sealed class NavTarget {
        object Dial : NavTarget() { override fun toString() = "dial" }
        data class Suggestion(val index: Int) : NavTarget() { override fun toString() = "suggestion:$index" }
        data class Result(val position: Int) : NavTarget() { override fun toString() = "result:$position" }
        object AiSettings : NavTarget() { override fun toString() = "ai-settings" }
    }

    /**
     * Trackpad highlight. Keyboard focus never leaves the search field (so typing always lands
     * there); this is a virtual selection over the rows below it, drawn by [dispatchDraw].
     */
    private var navPos: NavPos? = null
    private val navPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val navRect = android.graphics.Rect()
    private val navRectF = android.graphics.RectF()

    private fun navTargetRows(): List<List<NavTarget>> {
        val rows = mutableListOf<List<NavTarget>>()
        if (dialRow.visibility == VISIBLE) rows += listOf(NavTarget.Dial)
        if (suggestionsBox.visibility == VISIBLE) {
            for (i in 0 until suggestionsBox.childCount) rows += listOf(NavTarget.Suggestion(i))
        }
        if (resultsRecycler.visibility == VISIBLE) {
            val span = (resultsRecycler.layoutManager as? GridLayoutManager)?.spanCount ?: 1
            currentResults.indices.chunked(span).forEach { chunk -> rows += chunk.map { NavTarget.Result(it) } }
        }
        if (aiPanel.visibility == VISIBLE && aiSettingsLink.visibility == VISIBLE) rows += listOf(NavTarget.AiSettings)
        return rows
    }

    private fun navRows(rows: List<List<NavTarget>>) =
        rows.mapIndexed { i, row -> NavRow(row.size, if (row.first() is NavTarget.Result) -1 else i) }

    internal val navSelection: NavTarget?
        get() = navPos?.let { p -> navTargetRows().getOrNull(p.row)?.getOrNull(p.col) }

    val hasNavSelection: Boolean get() = navSelection != null

    fun clearNavSelection() {
        if (navPos == null) return
        navPos = null
        invalidate()
    }

    /**
     * Moves the trackpad highlight. Up/down are always consumed while search is shown, so focus
     * can't wander to the home screen behind it; left/right only while a row is highlighted,
     * otherwise they move the field's cursor. In an AI answer, up/down past the rows scroll it.
     */
    fun navigate(dRow: Int, dCol: Int): Boolean {
        if (visibility != VISIBLE) return false
        val targets = navTargetRows()
        val rows = navRows(targets)
        val from = navPos?.takeIf { it.row in rows.indices && it.col < rows[it.row].size }
        if (dRow == 0 && from == null) return false
        if (aiPanel.visibility == VISIBLE && dRow != 0) {
            val atEnd = if (dRow > 0) from?.row == rows.lastIndex || rows.isEmpty() else from == null
            if (atEnd && aiPanel.canScrollVertically(dRow)) {
                aiPanel.scrollBy(0, dRow * maxOf(aiPanel.height / 3, dp(48)))
                return true
            }
        }
        navPos = SearchNavigation.move(rows, from, dRow, dCol)
        navPos?.let { revealNavTarget(targets[it.row][it.col]) }
        invalidate()
        return true
    }

    /** Activates the highlighted row as a tap would. False when nothing is highlighted. */
    fun activateNavSelection(): Boolean {
        val target = navSelection ?: return false
        navPos = null
        invalidate()
        when (target) {
            NavTarget.Dial -> dial(currentDialDigits)
            is NavTarget.Suggestion -> suggestionsBox.getChildAt(target.index)?.performClick()
            is NavTarget.Result -> currentResults.getOrNull(target.position)?.let(::openResult)
            NavTarget.AiSettings -> aiSettingsLink.performClick()
        }
        return true
    }

    private fun revealNavTarget(target: NavTarget) {
        when (target) {
            is NavTarget.Result -> {
                resultsRecycler.scrollToPosition(target.position)
                // The item may only be laid out on the next pass; redraw the highlight then.
                resultsRecycler.post { invalidate() }
            }
            NavTarget.AiSettings -> {
                val content = aiSettingsLink.parent as View
                aiPanel.scrollTo(0, maxOf(0, aiSettingsLink.bottom + content.paddingBottom - aiPanel.height))
            }
            else -> {}
        }
    }

    private fun navTargetView(target: NavTarget): View? = when (target) {
        NavTarget.Dial -> dialRow
        is NavTarget.Suggestion -> suggestionsBox.getChildAt(target.index)
        is NavTarget.Result -> resultsRecycler.findViewHolderForAdapterPosition(target.position)?.itemView
        NavTarget.AiSettings -> aiSettingsLink
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val target = navSelection ?: return
        val view = navTargetView(target)?.takeIf { it.isShown } ?: return
        navRect.set(0, 0, view.width, view.height)
        offsetDescendantRectToMyCoords(view, navRect)
        val clipTo: View? = when (target) {
            is NavTarget.Result -> resultsRecycler
            NavTarget.AiSettings -> aiPanel
            else -> null
        }
        canvas.save()
        if (clipTo != null) canvas.clipRect(clipTo.left, clipTo.top, clipTo.right, clipTo.bottom)
        navRectF.set(navRect)
        navRectF.inset(dp(1).toFloat(), dp(1).toFloat())
        val radius = dp(6).toFloat()
        navPaint.color = navHighlightColor
        canvas.drawRoundRect(navRectF, radius, radius, navPaint)
        canvas.restore()
    }

    /**
     * A soft neutral fill, lighter than whatever is under it. The highlight always sits on the
     * overlay's own background, so contrast is judged against that: white on a dark (or mostly
     * see-through) background, black on a light one.
     */
    internal val navHighlightColor: Int
        get() {
            val bg = (background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.BLACK
            val lightBg = Color.alpha(bg) >= 0x80 && androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.5
            return if (lightBg) Color.argb(0x1A, 0, 0, 0) else Color.argb(0x1F, 255, 255, 255)
        }

    /**
     * Answers the current query with the configured AI model, in place of the results. Typing,
     * closing or reopening search cancels it. Returns false when there is no query to answer.
     */
    fun startAiAnswer(): Boolean {
        val query = lastQuery.trim()
        if (visibility != VISIBLE || query.isEmpty()) return false
        clearWebSuggestions()
        aiSession.start(query, aiConfigProvider())
        return true
    }

    private fun renderAi(state: AiState) {
        val active = state !is AiState.Idle
        clearNavSelection()
        aiPanel.visibility = if (active) VISIBLE else GONE
        resultsRecycler.visibility = if (active) GONE else VISIBLE
        if (!active) return
        if (suggestionsBox.visibility == VISIBLE) clearWebSuggestions()
        val loading = state is AiState.Loading || (state is AiState.Answer && state.text.isEmpty() && !state.done)
        aiProgress.visibility = if (loading) VISIBLE else GONE
        when (state) {
            is AiState.Answer -> {
                aiError.visibility = GONE
                aiSettingsLink.visibility = GONE
                if (state.done && state.text.isBlank()) {
                    aiText.visibility = GONE
                    aiError.visibility = VISIBLE
                    aiError.text = context.getString(R.string.ai_empty_answer)
                } else {
                    aiText.visibility = if (state.text.isEmpty()) GONE else VISIBLE
                    aiText.text = AiMarkdown.toSpanned(AiMarkdown.parse(state.text), markdownStyle)
                }
            }
            is AiState.Failed -> {
                aiText.visibility = GONE
                aiError.visibility = VISIBLE
                aiError.text = state.message
                aiSettingsLink.visibility = VISIBLE
            }
            else -> {
                aiText.visibility = GONE
                aiError.visibility = GONE
                aiSettingsLink.visibility = GONE
                aiPanel.scrollTo(0, 0)
            }
        }
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
        aiSession.cancel()
        clearWebSuggestions()
        clearNavSelection()
        visibility = GONE
        onDismiss?.invoke()
    }

    /** Synchronous and cheap enough to run on every keystroke; a repeat of the shown query is a no-op. */
    fun applyQuery(query: String) {
        if (query == appliedQuery) return
        lastQuery = query
        appliedQuery = query
        if (aiSession.isActive) aiSession.cancel()
        android.os.Trace.beginSection("SearchOverlay.applyQuery")
        try {
            onSearch(query)
        } finally {
            android.os.Trace.endSection()
        }
    }

    private fun dial(number: String) {
        try {
            // Encode # as %23 so it is not interpreted as URI fragment (otherwise dialer only receives "*" for *#06#)
            val telUri = "tel:" + number.replace("#", "%23")
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            dismiss()
        } catch (_: Exception) {
            Toast.makeText(context, R.string.dial_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * On Enter / Extended search. In command mode, an exactly typed command runs. A dialable number
     * dials, like tapping the dial row shown above the results. Otherwise the first result launches,
     * or the query goes to web search.
     */
    fun performExtendedSearch() {
        val trigger = commandTriggerProvider?.invoke()
        val commandMode = !trigger.isNullOrEmpty() && lastQuery.startsWith(trigger)
        if (commandMode) {
            val commandPart = lastQuery.removePrefix(trigger!!).trim()
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
        // Keypad letters ("wer") only dial when nothing matched; real digits always dial.
        val number = when {
            commandMode -> null
            DialNumber.dialable(lastQuery) != null -> DialNumber.dialable(lastQuery)
            currentResults.isEmpty() -> queryDialNumber
            else -> null
        }
        if (number != null) {
            dial(number)
            return
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
                is LaunchableItem.LauncherSettings -> context.startActivity(com.meowgi.launcher710.ui.settings.SettingsActivity.intent(context))
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
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(searchLaunchFlags))
                } catch (_: Exception) {
                    // No search app and no browser: say so rather than crash the launcher.
                    android.widget.Toast.makeText(context, "No app found to open the search", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            dismiss()
        }
    }

    /** Contact rows currently shown, carried into the next keystroke while its own lookup runs. */
    private var shownContacts: List<LaunchableItem.Contact> = emptyList()

    private fun onSearch(query: String) {
        val trigger = commandTriggerProvider?.invoke()
        if (!trigger.isNullOrEmpty() && query.startsWith(trigger)) {
            webSuggestionsAllowed = false
            queryDialNumber = null
            ContactSearchHelper.cancel()
            handleCommandSearch(query.removePrefix(trigger))
            return
        }
        val contactMode = contactModeProvider()
        if (contactMode == 2 && query.startsWith("@")) {
            webSuggestionsAllowed = false
            queryDialNumber = null
            handleAtContactSearch(query.removePrefix("@").trimStart())
            return
        }
        webSuggestionsAllowed = true
        val layout = dialerLayoutProvider().coerceIn(0, 1)
        val keypadDigits = convertToDialDigits(query, layout)
        val dialDigitsForAppSearch = keypadDigits.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() }
        val nq = SearchNormalizer.normalize(query)
        queryDialNumber = DialNumber.dialable(query, layout)
        queryNormalized = nq

        val filter = filterIndex
        if (filter != null) {
            ContactSearchHelper.cancel()
            val filtered = if (nq.isEmpty() && dialDigitsForAppSearch == null) {
                filter.map { it.item }
            } else {
                val repo = repository
                val ranked = mutableListOf<SearchRanker.Ranked<LaunchableItem>>()
                for (entry in filter) {
                    val tier = SearchRanker.tier(nq, dialDigitsForAppSearch, entry.normalizedLabel, entry.words, entry.normalizedPackage)
                    if (tier == SearchRanker.NO_MATCH) continue
                    val app = (entry.item as? LaunchableItem.App)?.app
                    val usage = if (app != null && repo != null) repo.usageKey(app.componentKey) else SearchRanker.NO_USAGE
                    // Empty sort label: ties keep the page's own order (the sort is stable).
                    ranked += SearchRanker.Ranked(entry.item, tier, usage, "")
                }
                SearchRanker.sort(ranked, repo?.usageRankingEnabled ?: false)
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
        updateDialRow(queryDialNumber ?: "")
    }

    /**
     * Runs on every publish, so it stays cheap: when suggestions don't apply it only cancels;
     * otherwise it hands the query to [WebSuggestions], where a repeat of the same query is a no-op.
     */
    private fun updateWebSuggestions(results: List<LaunchableItem>) {
        val query = lastQuery.trim()
        if (aiSession.isActive || !WebSuggestions.shouldRequest(webSuggestionsEnabled, webSuggestionsAllowed, query,
                dialable = queryDialNumber != null, hasTextMatch = results.any(::matchesText))
        ) {
            clearWebSuggestions()
            return
        }
        webSuggestions.request(query)
    }

    private fun matchesText(item: LaunchableItem): Boolean {
        val nq = queryNormalized
        if (nq.isEmpty()) return false
        val app = (item as? LaunchableItem.App)?.app
        val match = if (app != null) {
            SearchNormalizer.match(nq, null, app.normalizedLabel, app.normalizedWords, app.normalizedPackage)
        } else {
            val label = item.label.toString()
            SearchNormalizer.match(nq, null, SearchNormalizer.normalize(label), SearchNormalizer.words(label), "")
        }
        return match != SearchNormalizer.NO_MATCH
    }

    private fun showWebSuggestions(query: String, suggestions: List<String>) {
        if (visibility != VISIBLE || query != lastQuery.trim() || !webSuggestionsAllowed || queryDialNumber != null || aiSession.isActive ||
            currentResults.any(::matchesText)
        ) return
        clearNavSelection()
        suggestionsBox.removeAllViews()
        for (s in suggestions) suggestionsBox.addView(webSuggestionRow(s), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        suggestionsBox.visibility = if (suggestions.isEmpty()) GONE else VISIBLE
    }

    private fun clearWebSuggestions() {
        webSuggestions.cancel()
        if (suggestionsBox.visibility == VISIBLE) clearNavSelection()
        if (suggestionsBox.childCount > 0) suggestionsBox.removeAllViews()
        suggestionsBox.visibility = GONE
    }

    private fun webSuggestionRow(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(resources.getColor(R.color.bb_text_primary, null))
        typeface = font
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
        compoundDrawablePadding = dp(12)
        val icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_search)?.mutate()?.apply {
            setTint(resources.getColor(R.color.bb_text_secondary, null))
            setBounds(0, 0, dp(20), dp(20))
        }
        setCompoundDrawables(icon, null, null, null)
        contentDescription = "Search Google for $text"
        isFocusable = true
        isClickable = true
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { openWebSearchWithQuery(text) }
    }

    private fun updateDialRow(dialDigits: String) {
        if (dialDigits.isEmpty()) {
            if (dialRow.visibility == VISIBLE) clearNavSelection()
            dialRow.visibility = GONE
            return
        }
        if (dialRow.visibility != VISIBLE || dialDigits != currentDialDigits) clearNavSelection()
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
     * Keypad digits for matching app labels and contact numbers by digits (e.g. "zw0" finds
     * "710 Launcher"). Never shown or dialled: the dial row and Enter use [DialNumber.dialable].
     *
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
                        else -> DialNumber.keypadChar(ch, DialNumber.LAYOUT_QWERTY)?.let { sb.append(it) }
                    }
                }
            }
        }
        return sb.toString()
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
