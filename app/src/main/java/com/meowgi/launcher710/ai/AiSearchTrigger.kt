package com.meowgi.launcher710.ai

import android.app.Activity
import android.os.Bundle
import android.text.method.MetaKeyKeyListener
import android.view.KeyEvent
import java.lang.ref.WeakReference

/**
 * Lets Key Mapper (or anything else) ask the running launcher for an AI answer to the query in
 * the open search overlay. Fire it with [ACTION] either as an activity (handled by
 * [AiSearchActivity]) or as a broadcast to this package (registered by the launcher while it runs).
 */
object AiSearchTrigger {
    const val ACTION = "com.meowgi.launcher710.action.AI_SEARCH"

    private var target: WeakReference<() -> Unit>? = null

    /** Main thread only. The launcher holds the strong reference, so this never leaks it. */
    fun register(handler: () -> Unit) { target = WeakReference(handler) }

    fun unregister(handler: () -> Unit) { if (target?.get() === handler) target = null }

    /**
     * Whether Alt applies to a key: held or system-locked per the event's [metaState], or sticky
     * (1) / locked (2) in the text field's own key listener, per [fieldAltState]
     * (`MetaKeyKeyListener.getMetaState(text, META_ALT_ON)`).
     */
    fun altActive(metaState: Int, fieldAltState: Int): Boolean =
        metaState and (KeyEvent.META_ALT_ON or MetaKeyKeyListener.META_ALT_LOCKED) != 0 || fieldAltState != 0

    /** Returns false when no launcher is listening. Main thread only. */
    fun fire(): Boolean {
        val h = target?.get() ?: return false
        h()
        return true
    }
}

/**
 * Invisible trampoline for [AiSearchTrigger.ACTION]. It shares the launcher's task affinity, so
 * an external start lands on top of the home task (no new Recents task) and, with
 * Theme.NoDisplay, finishes before anything is drawn.
 */
class AiSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiSearchTrigger.fire()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
