package com.meowgi.launcher710.ai

import com.meowgi.launcher710.util.LauncherPrefs

data class AiConfig(val baseUrl: String, val apiKey: String, val model: String, val systemPrompt: String) {
    override fun toString() = "AiConfig(baseUrl=$baseUrl, model=$model, apiKey=•••)"
}

sealed class AiConfigResult {
    data class Ready(val config: AiConfig) : AiConfigResult()
    data class Unavailable(val message: String) : AiConfigResult()
}

object AiSettings {
    fun load(prefs: LauncherPrefs, secrets: AiSecrets): AiConfigResult =
        resolve(prefs.aiEnabled, prefs.aiBaseUrl, secrets.apiKey, secrets.hasApiKey, prefs.aiModel, prefs.aiSystemPrompt)

    fun resolve(enabled: Boolean, baseUrl: String, apiKey: String?, hasStoredKey: Boolean, model: String, systemPrompt: String): AiConfigResult {
        if (!enabled) return AiConfigResult.Unavailable("AI answers are off.")
        if (!AiProtocol.isValidBaseUrl(baseUrl)) return AiConfigResult.Unavailable("Set a valid base URL for AI answers.")
        // No key at all is allowed: local OpenAI-compatible servers often don't need one.
        if (apiKey.isNullOrBlank() && hasStoredKey) {
            return AiConfigResult.Unavailable("The saved API key can't be read on this device. Enter it again.")
        }
        if (model.isBlank()) return AiConfigResult.Unavailable("Pick a model for AI answers.")
        return AiConfigResult.Ready(AiConfig(AiProtocol.normalizeBaseUrl(baseUrl), apiKey.orEmpty(), model.trim(), systemPrompt))
    }
}
