package com.meowgi.launcher710.ui.settings

import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ui.dialogs.AppPickerWithSearchDialog
import com.meowgi.launcher710.ui.dialogs.IconPickerDialog
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.SearchCommandData
import kotlinx.coroutines.launch

internal fun SettingsActivity.getContactIconLabel(): String =
    if (!prefs.contactIconDrawableName.isNullOrBlank() && !prefs.contactIconPackPackage.isNullOrBlank())
        getString(R.string.contact_icon_custom)
    else
        getString(R.string.contact_icon_contacts_app)

internal fun SettingsActivity.showContactIconPicker() {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle(getString(R.string.contact_icon))
        .setItems(arrayOf(getString(R.string.contact_icon_contacts_app), getString(R.string.contact_icon_choose_pack))) { _, which ->
            when (which) {
                0 -> {
                    prefs.contactIconDrawableName = null
                    prefs.contactIconPackPackage = null
                    rebuildSettings()
                }
                1 -> {
                    val picker = IconPickerDialog(this,
                        onIconSelected = { drawableName, packPackage ->
                            prefs.contactIconDrawableName = drawableName
                            prefs.contactIconPackPackage = packPackage
                            rebuildSettings()
                        },
                        onReset = {
                            prefs.contactIconDrawableName = null
                            prefs.contactIconPackPackage = null
                            rebuildSettings()
                        }
                    )
                    picker.showPackPicker()
                }
            }
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

internal fun SettingsActivity.getCommandIconLabel(): String =
    if (!prefs.commandIconDrawableName.isNullOrBlank() && !prefs.commandIconPackPackage.isNullOrBlank())
        "Custom"
    else
        "Default"

internal fun SettingsActivity.showCommandIconPicker() {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Command icon")
        .setItems(arrayOf("Default", "Choose from icon pack")) { _, which ->
            when (which) {
                0 -> {
                    prefs.commandIconDrawableName = null
                    prefs.commandIconPackPackage = null
                    rebuildSettings()
                }
                1 -> {
                    val picker = IconPickerDialog(this,
                        onIconSelected = { drawableName, packPackage ->
                            prefs.commandIconDrawableName = drawableName
                            prefs.commandIconPackPackage = packPackage
                            rebuildSettings()
                        },
                        onReset = {
                            prefs.commandIconDrawableName = null
                            prefs.commandIconPackPackage = null
                            rebuildSettings()
                        }
                    )
                    picker.showPackPicker()
                }
            }
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

internal fun SettingsActivity.showCommandTriggerDialog() {
    val input = EditText(this).apply {
        setText(prefs.searchCommandTrigger ?: "")
        hint = "e.g. ./ or ~"
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Command trigger")
        .setMessage("Symbols only (no letters/numbers). Not allowed: * # + @")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) {
                prefs.searchCommandTrigger = null
                rebuildSettings()
                return@setPositiveButton
            }
            if (!prefs.isValidCommandTrigger(raw)) {
                Toast.makeText(this, "Use only symbols. * # + @ are not allowed.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            prefs.searchCommandTrigger = raw
            rebuildSettings()
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

internal fun SettingsActivity.showSearchCommandsManager() {
    val commands = prefs.getSearchCommands().toMutableList()
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    val rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    layout.addView(rowsContainer)

    fun getActionLabel(cmd: SearchCommandData): String {
        return when (cmd.actionType) {
            0 -> cmd.actionPackage?.let { pkg ->
                try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            } ?: "Set action"
            1 -> cmd.actionName ?: (if (!cmd.intentUri.isNullOrEmpty()) "Shortcut" else "Set action")
            else -> "Set action"
        }
    }

    fun refreshList() {
        rowsContainer.removeAllViews()
        for ((index, cmd) in commands.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val labelView = TextView(this).apply {
                text = cmd.name
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColor(R.color.bb_text_primary))
                textSize = 14f
                typeface = font
            }
            val actionLabel = TextView(this).apply {
                text = getActionLabel(cmd)
                setTextColor(getColor(R.color.bb_text_secondary))
                textSize = 12f
                typeface = font
            }
            val actionBtn = Button(this).apply {
                text = "Action"
                setOnClickListener {
                    AlertDialog.Builder(this@showSearchCommandsManager, dialogTheme)
                        .setTitle("Action for \"${cmd.name}\"")
                        .setItems(arrayOf("App", "Shortcut")) { _, which ->
                            when (which) {
                                0 -> {
                                    val items = installedApps()
                                    AppPickerWithSearchDialog.show(this@showSearchCommandsManager, "Choose app", items, onSelected = { pkg, name ->
                                        val updated = commands.toMutableList()
                                        if (index in updated.indices) {
                                            updated[index] = cmd.copy(actionType = 0, actionPackage = pkg, intentUri = null, actionName = name)
                                            prefs.setSearchCommands(updated)
                                            commands.clear()
                                            commands.addAll(updated)
                                            refreshList()
                                        }
                                    })
                                }
                                1 -> {
                                    onCommandShortcutSet = { refreshList() }
                                    pendingCommandIndexForShortcut = index
                                    try {
                                        commandShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                                    } catch (_: Exception) {
                                        Toast.makeText(this@showSearchCommandsManager, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                                        pendingCommandIndexForShortcut = null
                                        onCommandShortcutSet = null
                                    }
                                }
                            }
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                }
            }
            val removeBtn = Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    commands.removeAt(index)
                    prefs.setSearchCommands(commands)
                    refreshList()
                }
            }
            row.addView(labelView)
            row.addView(actionLabel)
            row.addView(actionBtn)
            row.addView(removeBtn)
            rowsContainer.addView(row)
        }
    }

    val addBtn = Button(this).apply {
        text = "Add command"
        setOnClickListener {
            val input = EditText(this@showSearchCommandsManager).apply {
                hint = "Command name (e.g. myMacro)"
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this@showSearchCommandsManager, dialogTheme)
                .setTitle("New command")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this@showSearchCommandsManager, "Enter a name", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    commands.add(SearchCommandData(name = name, actionType = 0, actionPackage = null, intentUri = null, actionName = null))
                    prefs.setSearchCommands(commands)
                    refreshList()
                    Toast.makeText(this@showSearchCommandsManager, "Now set the action for \"$name\"", Toast.LENGTH_SHORT).show()
                    AlertDialog.Builder(this@showSearchCommandsManager, dialogTheme)
                        .setTitle("Action for \"$name\"")
                        .setItems(arrayOf("App", "Shortcut")) { _, which ->
                            when (which) {
                                0 -> {
                                    val items = installedApps()
                                    val newIdx = commands.size - 1
                                    AppPickerWithSearchDialog.show(this@showSearchCommandsManager, "Choose app", items, onSelected = { pkg, appName ->
                                        if (newIdx in commands.indices) {
                                            commands[newIdx] = commands[newIdx].copy(actionType = 0, actionPackage = pkg, actionName = appName)
                                            prefs.setSearchCommands(commands)
                                            refreshList()
                                        }
                                    })
                                }
                                1 -> {
                                    onCommandShortcutSet = { refreshList() }
                                    pendingCommandIndexForShortcut = commands.size - 1
                                    try {
                                        commandShortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
                                    } catch (_: Exception) {
                                        Toast.makeText(this@showSearchCommandsManager, "No shortcut handler found", Toast.LENGTH_SHORT).show()
                                        pendingCommandIndexForShortcut = null
                                        onCommandShortcutSet = null
                                    }
                                }
                            }
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }
    }
    layout.addView(addBtn)
    refreshList()
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Search commands")
        .setView(cappedScroll(layout))
        .setNegativeButton("Done") { _, _ -> rebuildSettings() }
        .show()
}

internal fun SettingsActivity.showLaunchInjectAppOrShortcutMenu() {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("App or shortcut?")
        .setItems(arrayOf("App", "Shortcut")) { _, which ->
            when (which) {
                0 -> showLaunchInjectAppPicker()
                1 -> launchShortcutPicker(launchInjectShortcutLauncher)
            }
        }
        .show()
}

internal fun SettingsActivity.showLaunchInjectAppPicker() {
    val items = installedApps()
    AppPickerWithSearchDialog.show(this, "Choose app", items, onSelected = { pkg, name ->
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            prefs.searchEngineLaunchInjectIntentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            prefs.searchEngineLaunchInjectName = name
            rebuildSettings()
        } else {
            Toast.makeText(this, "Could not get launch intent", Toast.LENGTH_SHORT).show()
        }
    })
}

internal fun SettingsActivity.getSearchEngineAppLabel(): String =
    prefs.searchEnginePackage?.let { appLabel(it) } ?: "Choose app"

internal fun SettingsActivity.showSearchEngineAppPicker() {
    val items = installedApps()
    AppPickerWithSearchDialog.show(
        context = this,
        title = "Search app",
        items = items,
        onSelected = { pkg, _ ->
            prefs.searchEnginePackage = pkg
            rebuildSettings()
        },
        negativeButton = "Clear",
        onNegative = {
            prefs.searchEnginePackage = null
            rebuildSettings()
        }
    )
}

internal fun SettingsActivity.showSearchEngineIntentUriDialog() {
    val input = EditText(this).apply {
        setText(prefs.searchEngineIntentUri ?: "")
        hint = "intent:#Intent;..."
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Custom intent URI")
        .setMessage("Optional. Use %s for query placeholder if the app supports it.")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            prefs.searchEngineIntentUri = input.text.toString().trim().takeIf { it.isNotEmpty() }
            rebuildSettings()
        }
        .setNegativeButton("Clear") { _, _ ->
            prefs.searchEngineIntentUri = null
            rebuildSettings()
        }
        .show()
}

internal fun SettingsActivity.confirmResetUsageData() {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Reset usage data?")
        .setMessage("Clears launch counts and last-opened times for every app. Search ranking, the Frequent page and \"Last Opened\" / \"Most Used\" sorting start over. Favorites are kept.")
        .setPositiveButton("Reset") { _, _ ->
            val repo = AppRepository.get(this)
            lifecycleScope.launch {
                try {
                    repo.resetUsageData()
                    Toast.makeText(this@confirmResetUsageData, "Usage data reset", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@confirmResetUsageData, "Couldn't reset usage data", Toast.LENGTH_LONG).show()
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.getCurrencyListLabel(): String {
    val currencies = prefs.getSearchCurrencyList()
    return if (currencies.size <= 3) {
        currencies.joinToString(", ")
    } else {
        "${currencies.size} currencies"
    }
}

internal fun SettingsActivity.showCurrencyListManager() {
    val currencies = prefs.getSearchCurrencyList().toMutableList()
    val supportedCurrencies = listOf(
        "USD", "EUR", "GBP", "TRY", "JPY", "IDR", "AUD", "CAD", "CHF", "CNY", 
        "HKD", "INR", "KRW", "MXN", "NOK", "NZD", "PLN", "RUB", "SEK", "SGD", 
        "ZAR", "BGN", "BRL", "CZK", "DKK", "HUF", "ILS", "ISK", "PHP", "RON", "THB"
    )
    
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    val rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    layout.addView(rowsContainer)

    fun refreshList() {
        rowsContainer.removeAllViews()
        for ((index, currency) in currencies.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val labelView = TextView(this).apply {
                text = currency
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColor(R.color.bb_text_primary))
                textSize = 14f
                typeface = font
            }
            val upBtn = Button(this).apply {
                text = "↑"
                isEnabled = index > 0
                setOnClickListener {
                    if (index > 0) {
                        val temp = currencies[index]
                        currencies[index] = currencies[index - 1]
                        currencies[index - 1] = temp
                        prefs.setSearchCurrencyList(currencies)
                        refreshList()
                    }
                }
            }
            val downBtn = Button(this).apply {
                text = "↓"
                isEnabled = index < currencies.size - 1
                setOnClickListener {
                    if (index < currencies.size - 1) {
                        val temp = currencies[index]
                        currencies[index] = currencies[index + 1]
                        currencies[index + 1] = temp
                        prefs.setSearchCurrencyList(currencies)
                        refreshList()
                    }
                }
            }
            val removeBtn = Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    currencies.removeAt(index)
                    prefs.setSearchCurrencyList(currencies)
                    refreshList()
                    rebuildSettings()
                }
            }
            row.addView(labelView)
            row.addView(upBtn)
            row.addView(downBtn)
            row.addView(removeBtn)
            rowsContainer.addView(row)
        }
    }

    val addBtn = Button(this).apply {
        text = "Add currency"
        setOnClickListener {
            val availableCurrencies = supportedCurrencies.filter { it !in currencies }
            if (availableCurrencies.isEmpty()) {
                Toast.makeText(this@showCurrencyListManager, "All supported currencies added", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this@showCurrencyListManager, dialogTheme)
                .setTitle("Add currency")
                .setItems(availableCurrencies.toTypedArray()) { _, which ->
                    val selected = availableCurrencies[which]
                    currencies.add(selected)
                    prefs.setSearchCurrencyList(currencies)
                    refreshList()
                    rebuildSettings()
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }
    }
    layout.addView(addBtn)
    refreshList()
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Display currencies")
        .setMessage("Currencies to show when converting without a target")
        .setView(cappedScroll(layout))
        .setNegativeButton("Done") { _, _ -> rebuildSettings() }
        .show()
}
