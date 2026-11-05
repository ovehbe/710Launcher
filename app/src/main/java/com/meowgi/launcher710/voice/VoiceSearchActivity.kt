package com.meowgi.launcher710.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Exported trampoline for voice search; can be launched from key mappers.
 * Triggers voice dictation by passing an Intent extra directly to LauncherActivity.
 * Draws nothing and finishes immediately. No broadcast race condition.
 */
class VoiceSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Launch LauncherActivity with voice trigger extra (no broadcast race)
        val launcherIntent = Intent(this, com.meowgi.launcher710.LauncherActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TRIGGER_VOICE_SEARCH, true)
        }
        startActivity(launcherIntent)
        
        finish()
    }
    
    companion object {
        const val EXTRA_TRIGGER_VOICE_SEARCH = "trigger_voice_search"
    }
}
