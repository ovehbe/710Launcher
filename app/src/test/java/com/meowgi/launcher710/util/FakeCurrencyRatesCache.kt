package com.meowgi.launcher710.util

import kotlinx.coroutines.delay

/**
 * Fake currency rates cache for deterministic testing.
 * Provides fixed rates without network calls.
 */
class FakeCurrencyRatesCache : CurrencyRatesCacheInterface {
    
    private val fixedRates = mapOf(
        "USD" to 1.0,
        "EUR" to 0.85,
        "GBP" to 0.73,
        "JPY" to 149.0,
        "TRY" to 32.5,
        "IDR" to 15420.0,
        "CNY" to 7.2
    )
    
    var delayMs: Long = 0
    var shouldError: Boolean = false
    
    override suspend fun getRates(forceRefresh: Boolean): CurrencyRatesCache.RatesResult {
        if (delayMs > 0) {
            delay(delayMs)
        }
        
        return if (shouldError) {
            CurrencyRatesCache.RatesResult.Error("Test error")
        } else {
            CurrencyRatesCache.RatesResult.Success(fixedRates, "USD", System.currentTimeMillis())
        }
    }
}
