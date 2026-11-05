package com.meowgi.launcher710.voice

import android.app.Activity
import android.os.Bundle

/**
 * Exported trampoline for voice search; can be launched from key mappers.
 * Triggers voice dictation on the open search overlay or opens search with voice active.
 * Draws nothing and finishes immediately.
 */
class VoiceSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Broadcast to LauncherActivity to trigger voice search
        val intent = android.content.Intent(ACTION_TRIGGER_VOICE_SEARCH)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        finish()
    }
    
    companion object {
        const val ACTION_TRIGGER_VOICE_SEARCH = "com.meowgi.launcher710.TRIGGER_VOICE_SEARCH"
    }
}
