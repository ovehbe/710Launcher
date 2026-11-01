package com.meowgi.launcher710.ui.settings

import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.meowgi.launcher710.ai.AiCall
import com.meowgi.launcher710.ai.AiClient
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.ai.AiEvent
import com.meowgi.launcher710.ai.AiException
import com.meowgi.launcher710.ai.AiProtocol
import com.meowgi.launcher710.ai.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun SettingsActivity.aiInput(text: String, hint: String, inputType: Int) = EditText(this).apply {
    setText(text)
    this.hint = hint
    this.inputType = inputType
    setSelection(this.text.length)
    setPadding(dp(16), dp(12), dp(16), dp(12))
}

internal fun SettingsActivity.showAiBaseUrlDialog() {
    val input = aiInput(prefs.aiBaseUrl, AiProtocol.DEFAULT_BASE_URL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Base URL")
        .setMessage("OpenAI-compatible API root, without /chat/completions.")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val raw = input.text.toString().trim()
            if (!AiProtocol.isValidBaseUrl(raw)) {
                Toast.makeText(this, "Enter an http:// or https:// URL.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val url = AiProtocol.normalizeBaseUrl(raw)
            if (url != prefs.aiBaseUrl) prefs.aiFetchedModels = emptyList()
            prefs.aiBaseUrl = url
            rebuildSettings()
        }
        .setNeutralButton("Default") { _, _ ->
            prefs.aiBaseUrl = AiProtocol.DEFAULT_BASE_URL
            rebuildSettings()
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

/** The saved key is never shown or prefilled; entering a new one replaces it. */
internal fun SettingsActivity.showAiApiKeyDialog() {
    val input = aiInput("", if (aiSecrets.hasApiKey) "Saved — type to replace" else "sk-…",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val builder = AlertDialog.Builder(this, dialogTheme)
        .setTitle("API key")
        .setMessage("Stored encrypted on this device. Leave empty for servers that need no key.")
        .setView(input)
        .setPositiveButton("Save") { _, _ ->
            val key = input.text.toString().trim()
            if (key.isNotEmpty()) aiSecrets.apiKey = key
            rebuildSettings()
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
    if (aiSecrets.hasApiKey) {
        builder.setNeutralButton("Remove") { _, _ ->
            aiSecrets.apiKey = null
            rebuildSettings()
        }
    }
    builder.show()
}

internal fun SettingsActivity.showAiModelPicker() {
    val models = prefs.aiFetchedModels
    if (models.isEmpty()) {
        showAiModelManualDialog()
        return
    }
    val items = (models + "Enter manually…").toTypedArray()
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Model")
        .setSingleChoiceItems(items, models.indexOf(prefs.aiModel)) { dialog, which ->
            dialog.dismiss()
            if (which == models.size) {
                showAiModelManualDialog()
            } else {
                prefs.aiModel = models[which]
                rebuildSettings()
            }
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

private fun SettingsActivity.showAiModelManualDialog() {
    val input = aiInput(prefs.aiModel, "e.g. gpt-4o-mini", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("Model")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            prefs.aiModel = input.text.toString().trim()
            rebuildSettings()
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

internal fun SettingsActivity.showAiSystemPromptDialog() {
    val input = aiInput(prefs.aiSystemPrompt, "Optional, e.g. Answer briefly.",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
        minLines = 3
        maxLines = 8
    }
    AlertDialog.Builder(this, dialogTheme)
        .setTitle("System prompt")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            prefs.aiSystemPrompt = input.text.toString().trim()
            rebuildSettings()
        }
        .setNeutralButton("Clear") { _, _ ->
            prefs.aiSystemPrompt = ""
            rebuildSettings()
        }
        .setNegativeButton(getString(android.R.string.cancel), null)
        .show()
}

/** Progress dialog whose Cancel aborts [call]; disconnecting can touch the socket, so it runs off main. */
private fun SettingsActivity.aiProgressDialog(title: String, message: String, call: AiCall): AlertDialog =
    AlertDialog.Builder(this, dialogTheme)
        .setTitle(title)
        .setMessage(message)
        .setCancelable(true)
        .setNegativeButton(getString(android.R.string.cancel), null)
        .create()
        .apply {
            setOnDismissListener { lifecycleScope.launch(Dispatchers.IO) { call.cancel() } }
            show()
        }

private fun SettingsActivity.showAiResult(title: String, message: String) {
    AlertDialog.Builder(this, dialogTheme)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()
}

internal fun SettingsActivity.fetchAiModels() {
    val base = prefs.aiBaseUrl
    if (!AiProtocol.isValidBaseUrl(base)) {
        showAiResult("Fetch models", "Set a valid base URL first.")
        return
    }
    val key = aiSecrets.apiKey.orEmpty()
    val call = AiCall()
    val progress = aiProgressDialog("Fetch models", "Loading models from ${AiProtocol.modelsUrl(base)}…", call)
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            try {
                Result.success(AiClient.fetchModels(base, key, call))
            } catch (e: AiException) {
                Result.failure(e)
            }
        }
        if (call.isCancelled) return@launch
        progress.setOnDismissListener(null)
        progress.dismiss()
        result.onSuccess { models ->
            if (models.isEmpty()) {
                showAiResult("Fetch models", "The server returned no models. Enter the model name manually.")
            } else {
                prefs.aiFetchedModels = models
                rebuildSettings()
                showAiModelPicker()
            }
        }.onFailure { showAiResult("Fetch models failed", it.message.orEmpty()) }
    }
}

internal fun SettingsActivity.testAi() {
    val config = when (val r = AiSettings.resolve(true, prefs.aiBaseUrl, aiSecrets.apiKey, aiSecrets.hasApiKey, prefs.aiModel, prefs.aiSystemPrompt)) {
        is AiConfigResult.Ready -> r.config
        is AiConfigResult.Unavailable -> {
            showAiResult("Test", r.message)
            return
        }
    }
    val call = AiCall()
    val progress = aiProgressDialog("Test", "Asking ${config.model}…", call)
    lifecycleScope.launch {
        val started = SystemClock.elapsedRealtime()
        var firstTokenMs = -1L
        val text = StringBuilder()
        var failure: String? = null
        withContext(Dispatchers.IO) {
            AiClient.streamChat(config, "Reply with OK.", call) { event ->
                when (event) {
                    is AiEvent.Token -> {
                        if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - started
                        text.append(event.text)
                    }
                    is AiEvent.Failure -> failure = event.message
                    AiEvent.Done -> Unit
                }
            }
        }
        if (call.isCancelled) return@launch
        val totalMs = SystemClock.elapsedRealtime() - started
        progress.setOnDismissListener(null)
        progress.dismiss()
        val error = failure
        if (error != null) {
            showAiResult("Test failed", error)
        } else {
            val reply = text.toString().trim().ifEmpty { "(empty reply)" }
            val timing = if (firstTokenMs >= 0) "First token ${firstTokenMs} ms, done in $totalMs ms." else "Done in $totalMs ms."
            showAiResult("Test passed", "${config.model} replied: ${reply.take(300)}\n\n$timing")
        }
    }
}
