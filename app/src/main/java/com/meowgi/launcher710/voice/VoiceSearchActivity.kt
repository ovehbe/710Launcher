package com.meowgi.launcher710.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Exported trampoline for voice search; can be launched from key mappers.
 * Triggers voice dictation on the open search overlay or opens search with voice active.
 * Draws nothing and finishes immediately. Ensures LauncherActivity is started/resumed first.
 */
class VoiceSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start LauncherActivity first (or bring to front if already running)
        val launcherIntent = Intent(this, com.meowgi.launcher710.LauncherActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(launcherIntent)
        
        // Broadcast to trigger voice search (LauncherActivity's receiver will handle it)
        val voiceIntent = Intent(ACTION_TRIGGER_VOICE_SEARCH).apply {
            setPackage(packageName)
        }
        sendBroadcast(voiceIntent)
        
        finish()
    }
    
    companion object {
        const val ACTION_TRIGGER_VOICE_SEARCH = "com.meowgi.launcher710.TRIGGER_VOICE_SEARCH"
    }
}
