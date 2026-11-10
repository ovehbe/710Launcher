package com.meowgi.launcher710.util

/**
 * Interface for currency rates cache to enable testing with fakes.
 */
interface CurrencyRatesCacheInterface {
    suspend fun getRates(forceRefresh: Boolean = false): CurrencyRatesCache.RatesResult
}
