package com.meowgi.launcher710.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Controllable fake cache for testing async race conditions.
 * Allows tests to block getRates() calls and release them on demand.
 * This proves old async work can complete after newer work, and generation tracking prevents stale overwrites.
 */
class ControllableFakeCurrencyRatesCache : CurrencyRatesCacheInterface {
    // Shared fake rates matching FakeCurrencyRatesCache
    private val fakeRates = mapOf(
        "TRY" to 32.50,
        "EUR" to 0.91,
        "USD" to 1.0,
        "JPY" to 155.0,
        "IDR" to 15500.0
    )
    
    // Deferred completion handle - null means immediate response
    private var blocked: CompletableDeferred<Unit>? = null
    
    /**
     * Block the next getRates() call until release() is called.
     * Must be called BEFORE the query that should block.
     */
    fun blockNext() {
        blocked = CompletableDeferred()
    }
    
    /**
     * Release any blocked getRates() call.
     * If no call is blocked, this is a no-op.
     */
    fun release() {
        blocked?.complete(Unit)
        blocked = null
    }
    
    override suspend fun getRates(forceRefresh: Boolean): CurrencyRatesCache.RatesResult {
        // If blocked, wait for release (consumes the block for THIS call only)
        val blockToWait = blocked
        if (blockToWait != null) {
            blocked = null  // Clear immediately so next call doesn't block
            blockToWait.await()
        }
        
        // Small delay to ensure async behavior is observable
        delay(10)
        
        return CurrencyRatesCache.RatesResult.Success(
            rates = fakeRates,
            base = "USD",
            timestamp = System.currentTimeMillis()
        )
    }
}
