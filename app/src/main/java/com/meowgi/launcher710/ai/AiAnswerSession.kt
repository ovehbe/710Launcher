package com.meowgi.launcher710.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AiState {
    object Idle : AiState()
    data class Loading(val query: String) : AiState()
    data class Answer(val query: String, val text: String, val done: Boolean) : AiState()
    data class Failed(val query: String, val message: String) : AiState()
}

/**
 * One AI answer at a time for the search overlay. Main thread only. [start] cancels any previous
 * answer; [cancel] stops the request (aborting the HTTP read) and returns to [AiState.Idle].
 * [onState] never sees anything from a request after it was cancelled or replaced. Tokens arriving
 * faster than the main thread draws are coalesced into one update.
 */
class AiAnswerSession(
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val onState: (AiState) -> Unit
) {
    /** Replaces the network call; runs on [Dispatchers.IO]. */
    internal var streamer: (AiConfig, String, AiCall, (AiEvent) -> Unit) -> Unit = AiClient::streamChat

    private val scope = CoroutineScope(SupervisorJob() + main)
    private var job: Job? = null
    private var call: AiCall? = null
    private var generation = 0

    var state: AiState = AiState.Idle
        private set

    val isActive: Boolean get() = state !is AiState.Idle

    fun start(query: String, config: AiConfigResult) {
        cancel()
        val gen = ++generation
        when (config) {
            is AiConfigResult.Unavailable -> { set(AiState.Failed(query, config.message)); return }
            is AiConfigResult.Ready -> set(AiState.Loading(query))
        }
        val cfg = config.config
        val c = AiCall().also { call = it }
        val text = StringBuilder()
        var pending = false
        var ended: AiEvent? = null
        val lock = Any()

        fun publish() {
            if (gen != generation) return
            val (snapshot, end) = synchronized(lock) { pending = false; text.toString() to ended }
            when (end) {
                is AiEvent.Failure -> set(AiState.Failed(query, end.message))
                else -> if (end != null || snapshot.isNotEmpty()) set(AiState.Answer(query, snapshot, done = end != null))
            }
        }

        job = scope.launch {
            withContext(Dispatchers.IO) {
                streamer(cfg, query, c) { event ->
                    val post = synchronized(lock) {
                        when (event) {
                            is AiEvent.Token -> text.append(event.text)
                            is AiEvent.Done, is AiEvent.Failure -> if (ended == null) ended = event
                        }
                        !pending.also { pending = true }
                    }
                    if (post) scope.launch { publish() }
                }
            }
            // The streamer returned without ending the answer (e.g. a fake in tests); close it out.
            val needsEnd = synchronized(lock) { (ended == null).also { if (it) ended = AiEvent.Done } }
            if (needsEnd) publish()
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        call?.let { c -> scope.launch(Dispatchers.IO) { c.cancel() } }
        call = null
        if (state !is AiState.Idle) set(AiState.Idle)
    }

    private fun set(s: AiState) {
        state = s
        onState(s)
    }
}
