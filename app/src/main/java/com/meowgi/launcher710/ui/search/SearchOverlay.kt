package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.ui.appgrid.AppAdapter
import com.meowgi.launcher710.util.AppRepository

class SearchOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val searchInput: EditText
    private val dialRow: LinearLayout
    private val dialText: TextView
    private val extendedSearchBtn: TextView
    private val resultsRecycler: RecyclerView
    private val adapter: AppAdapter
    private val font: Typeface? = ResourcesCompat.getFont(context, R.font.bbalphas)

    var repository: AppRepository? = null
    var onDismiss: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(resources.getColor(R.color.bb_overlay_dark, null))
        setPadding(dp(12), dp(8), dp(12), dp(8))

        val searchBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.search_bar_bg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        searchInput = EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            setTextColor(resources.getColor(R.color.bb_search_text, null))
            setHintTextColor(0xFF888888.toInt())
            textSize = 14f
            typeface = font
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchBar.addView(searchInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val searchIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        searchBar.addView(searchIcon, LayoutParams(dp(32), dp(32)))
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))

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
            text = context.getString(R.string.extended_search)
            textSize = 13f
            setTextColor(resources.getColor(R.color.bb_text_secondary, null))
            typeface = font
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { openWebSearch() }
        }
        addView(extendedSearchBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultsRecycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        adapter = AppAdapter(
            context,
            onClick = { app ->
                repository?.launchApp(app)
                dismiss()
            },
            onLongClick = { _, _ -> }
        )
        resultsRecycler.adapter = adapter
        addView(resultsRecycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onSearch(s?.toString() ?: "") }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { openWebSearch(); true } else false
        }

        setOnClickListener { dismiss() }
    }

    fun show() {
        visibility = VISIBLE
        searchInput.setText("")
        searchInput.requestFocus()
    }

    fun dismiss() {
        visibility = GONE
        searchInput.setText("")
        onDismiss?.invoke()
    }

    private fun onSearch(query: String) {
        val repo = repository ?: return
        val results = repo.searchApps(query)
        adapter.submitList(results)

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

    private fun openWebSearch() {
        val query = searchInput.text.toString()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
