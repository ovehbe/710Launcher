package com.meowgi.launcher710

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Trampoline activity for button/key assignment: opens the launcher and shows the home
 * context menu (Add widget, Choose wallpaper, Settings). Assign this activity to a
 * hardware key or shortcut to open that menu.
 */
class OpenHomeMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LauncherActivity.OPEN_HOME_MENU_EXTRA, true)
        })
        finish()
    }
}
