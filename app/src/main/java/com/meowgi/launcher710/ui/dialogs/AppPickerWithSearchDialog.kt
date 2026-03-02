package com.meowgi.launcher710.ui.dialogs

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.meowgi.launcher710.R

object AppPickerWithSearchDialog {

    /**
     * Show a searchable app picker.
     * @param items List of (id, displayLabel) - id is packageName or componentName depending on use
     * @param onSelected Called with (id, label) when user selects an item
     * @param negativeButton Button label (default "Cancel")
     * @param onNegative Optional action for negative button (e.g. "Clear")
     */
    fun show(
        context: Context,
        title: String,
        items: List<Pair<String, String>>,
        onSelected: (id: String, label: String) -> Unit,
        negativeButton: String = "Cancel",
        onNegative: (() -> Unit)? = null
    ) {
        if (items.isEmpty()) return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var dialog: AlertDialog? = null

        val searchInput = EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val itemViews = items.map { (id, label) ->
            TextView(context).apply {
                text = label
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    onSelected(id, label)
                    dialog?.dismiss()
                }
                tag = Pair(id, label)
            }
        }
        itemViews.forEach { container.addView(it) }
        scroll.addView(container)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchInput)
            addView(scroll)
        }

        val builder = AlertDialog.Builder(context, R.style.BBDialogTheme)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(negativeButton) { _, _ ->
                onNegative?.invoke()
            }
        dialog = builder.show()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = (s?.toString() ?: "").trim().lowercase()
                for (tv in itemViews) {
                    val label = (tv.tag as Pair<*, *>).second as String
                    tv.visibility = if (query.isEmpty() || label.lowercase().contains(query)) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }
            }
        })
    }
}
