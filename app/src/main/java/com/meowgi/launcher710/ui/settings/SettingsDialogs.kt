package com.meowgi.launcher710.ui.settings

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.meowgi.launcher710.ui.dialogs.AppPickerWithSearchDialog

/** FrameLayout that never exceeds maxHeightPx so a child ScrollView can scroll. */
internal class MaxHeightFrameLayout(
    context: Context,
    private val maxHeightPx: Int
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val hSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (hMode == View.MeasureSpec.UNSPECIFIED || hSize > maxHeightPx) {
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}

/** Wraps dialog content in a scroll view capped at 60% of the screen height. */
internal fun SettingsActivity.cappedScroll(content: View): View {
    val maxHeightPx = (resources.displayMetrics.heightPixels * 0.6).toInt().coerceAtLeast(dp(240))
    val scroll = ScrollView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true
        addView(content)
    }
    return MaxHeightFrameLayout(this, maxHeightPx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(scroll)
    }
}

/** Installed apps as (package, label), sorted by label; apps without a label are skipped. */
internal fun SettingsActivity.installedApps(exclude: Set<String> = emptySet()): List<Pair<String, String>> =
    packageManager.getInstalledApplications(0)
        .mapNotNull { info ->
            if (info.packageName in exclude) return@mapNotNull null
            val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { null }
            if (label.isNullOrBlank()) null else (info.packageName to label)
        }
        .sortedBy { it.second.lowercase() }

internal fun SettingsActivity.pickInstalledApp(title: String, onSelected: (pkg: String, label: String) -> Unit) {
    AppPickerWithSearchDialog.show(this, title, installedApps(), onSelected = onSelected)
}

internal fun SettingsActivity.appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) { pkg }

internal fun SettingsActivity.launchShortcutPicker(launcher: ActivityResultLauncher<Intent>): Boolean = try {
    launcher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT))
    true
} catch (_: Exception) {
    Toast.makeText(this, "No shortcut handler found", Toast.LENGTH_SHORT).show()
    false
}

internal fun SettingsActivity.openSystemScreen(intent: Intent, failure: String, hint: String? = null) {
    try {
        startActivity(intent)
        if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        Toast.makeText(this, failure, Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.pickWallpaper() {
    try {
        val intent = Intent(Intent.ACTION_SET_WALLPAPER)
        startActivity(Intent.createChooser(intent, "Choose Wallpaper"))
    } catch (_: Exception) {
        Toast.makeText(this, "No wallpaper picker found", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.pickLiveWallpaper() {
    try {
        startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    } catch (_: Exception) {
        Toast.makeText(this, "No live wallpaper picker found", Toast.LENGTH_SHORT).show()
    }
}
