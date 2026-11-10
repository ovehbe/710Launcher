package com.meowgi.launcher710.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages exchange rate fetching and caching for the currency converter.
 * Uses Frankfurter API (api.frankfurter.app) which provides free ECB exchange rates
 * without requiring an API key.
 */
class CurrencyRatesCache(context: Context) : CurrencyRatesCacheInterface {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("currency_rates_cache", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "CurrencyRatesCache"
        private const val API_BASE_URL = "https://api.frankfurter.app"
        private const val CACHE_KEY_RATES = "rates_json"
        private const val CACHE_KEY_TIMESTAMP = "rates_timestamp"
        private const val CACHE_KEY_BASE = "rates_base"
        
        // Refresh rates at most every 4 hours
        private const val CACHE_VALIDITY_MS = 4 * 60 * 60 * 1000L
        
        // Network timeout
        private const val TIMEOUT_MS = 5000
    }

    /**
     * Result of a rate fetch attempt.
     */
    sealed class RatesResult {
        data class Success(val rates: Map<String, Double>, val base: String, val timestamp: Long) : RatesResult()
        data class Cached(val rates: Map<String, Double>, val base: String, val timestamp: Long) : RatesResult()
        data class Error(val message: String) : RatesResult()
    }

    /**
     * Get exchange rates, preferring cache if fresh, otherwise fetching from network.
     * Always returns immediately with cache if available, even if stale.
     * On IO dispatcher - never blocks main thread.
     */
    override suspend fun getRates(forceRefresh: Boolean): RatesResult = withContext(Dispatchers.IO) {
        val cached = getCachedRates()
        val now = System.currentTimeMillis()
        
        // Return cached rates if fresh and not forcing refresh
        if (!forceRefresh && cached is RatesResult.Cached) {
            val age = now - cached.timestamp
            if (age < CACHE_VALIDITY_MS) {
                return@withContext cached
            }
        }
        
        // Try to fetch fresh rates
        try {
            val freshRates = fetchRatesFromNetwork()
            if (freshRates is RatesResult.Success) {
                cacheRates(freshRates.rates, freshRates.base, freshRates.timestamp)
                return@withContext freshRates
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch rates: ${e.message}")
        }
        
        // Fall back to cached rates if available, even if stale
        if (cached is RatesResult.Cached) {
            return@withContext cached
        }
        
        return@withContext RatesResult.Error("No cached rates available and network fetch failed")
    }

    /**
     * Get cached rates if available.
     */
    private fun getCachedRates(): RatesResult {
        val ratesJson = prefs.getString(CACHE_KEY_RATES, null) ?: return RatesResult.Error("No cache")
        val timestamp = prefs.getLong(CACHE_KEY_TIMESTAMP, 0L)
        val base = prefs.getString(CACHE_KEY_BASE, "EUR") ?: "EUR"
        
        if (timestamp == 0L) return RatesResult.Error("No cache")
        
        try {
            val json = JSONObject(ratesJson)
            val rates = mutableMapOf<String, Double>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                rates[key.uppercase()] = json.getDouble(key)
            }
            return RatesResult.Cached(rates, base, timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached rates: ${e.message}")
            return RatesResult.Error("Cache corrupted")
        }
    }

    /**
     * Fetch rates from Frankfurter API.
     * Format: GET https://api.frankfurter.app/latest
     * Response: {"amount":1.0,"base":"EUR","date":"2024-01-01","rates":{"USD":1.10,"GBP":0.86,...}}
     */
    private fun fetchRatesFromNetwork(): RatesResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$API_BASE_URL/latest")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return RatesResult.Error("HTTP $responseCode")
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            val base = json.optString("base", "EUR").uppercase()
            val ratesObj = json.getJSONObject("rates")
            val rates = mutableMapOf<String, Double>()
            
            // Add base currency with rate 1.0
            rates[base] = 1.0
            
            // Add all other currencies
            val keys = ratesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                rates[key.uppercase()] = ratesObj.getDouble(key)
            }
            
            val timestamp = System.currentTimeMillis()
            return RatesResult.Success(rates, base, timestamp)
            
        } catch (e: Exception) {
            Log.w(TAG, "Network fetch failed: ${e.message}")
            return RatesResult.Error("Network error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Cache rates to SharedPreferences.
     */
    private fun cacheRates(rates: Map<String, Double>, base: String, timestamp: Long) {
        try {
            val json = JSONObject()
            for ((currency, rate) in rates) {
                json.put(currency, rate)
            }
            prefs.edit()
                .putString(CACHE_KEY_RATES, json.toString())
                .putLong(CACHE_KEY_TIMESTAMP, timestamp)
                .putString(CACHE_KEY_BASE, base)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache rates: ${e.message}")
        }
    }

    /**
     * Clear cached rates.
     */
    fun clearCache() {
        prefs.edit()
            .remove(CACHE_KEY_RATES)
            .remove(CACHE_KEY_TIMESTAMP)
            .remove(CACHE_KEY_BASE)
            .apply()
    }
}
