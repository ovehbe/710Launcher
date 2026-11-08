package com.meowgi.launcher710.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for R2: Stale async currency result race conditions.
 * 
 * FAILING: Demonstrates that older slower coroutines can overwrite newer results.
 */
class CurrencyStaleAsyncTest {
    
    @Test
    fun `R2 FAILING - slower old query should not overwrite newer results`() = runBlocking {
        // Simulate fast typing where second query completes before first
        
        var currentResults: List<String>? = null
        
        // Launch "slow" query
        val slowJob = launch {
            delay(100) // Slow network/computation
            currentResults = listOf("old-result-1", "old-result-2")
        }
        
        // Launch "fast" query
        val fastJob = launch {
            delay(10) // Fast response
            currentResults = listOf("new-result-1", "new-result-2")
        }
        
        // Wait for both
        fastJob.join()
        slowJob.join()
        
        // EXPECTED: Should have new results (from fast query)
        // ACTUAL: Has old results (slow query overwrote)
        assertEquals("Should preserve newer results", 
            listOf("new-result-1", "new-result-2"), 
            currentResults)
        
        // This test demonstrates the race - it will FAIL because
        // slowJob overwrites after fastJob completes
    }
    
    @Test
    fun `R2 solution - query generation tracking prevents stale overwrites`() = runBlocking {
        // Proper solution: track query generation and ignore stale results
        
        var generation = 0
        var currentResults: List<String>? = null
        
        // First query
        val gen1 = ++generation
        val job1 = launch {
            delay(100)
            // Check if still current before writing
            if (gen1 == generation) {
                currentResults = listOf("result-1")
            }
        }
        
        // Second query (user typed more)
        val gen2 = ++generation
        val job2 = launch {
            delay(10)
            if (gen2 == generation) {
                currentResults = listOf("result-2")
            }
        }
        
        job2.join()
        job1.join()
        
        // With generation tracking, newer results preserved
        assertEquals(listOf("result-2"), currentResults)
    }
}
