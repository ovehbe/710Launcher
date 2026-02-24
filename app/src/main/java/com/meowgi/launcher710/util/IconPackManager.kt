package com.meowgi.launcher710.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class IconPackManager(private val context: Context) {

    private val pm = context.packageManager
    private var iconMap = mutableMapOf<String, String>()
    private var iconPackResources: Resources? = null
    private var iconPackPackage: String? = null
    var currentPackage: String? = null
        private set

    fun getAvailableIconPacks(): List<Pair<String, String>> {
        val packs = mutableListOf<Pair<String, String>>()
        val themes = listOf(
            "com.novalauncher.THEME",
            "org.adw.launcher.THEMES",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme"
        )
        for (action in themes) {
            val intent = Intent(action)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                if (packs.none { it.first == pkg }) {
                    packs.add(pkg to label)
                }
            }
        }
        return packs
    }

    fun loadIconPack(packageName: String): Boolean {
        iconMap.clear()
        iconPackPackage = packageName
        currentPackage = packageName

        try {
            iconPackResources = pm.getResourcesForApplication(packageName)
            val resId = iconPackResources!!.getIdentifier("appfilter", "xml", packageName)

            val parser = if (resId != 0) {
                iconPackResources!!.getXml(resId)
            } else {
                val assets = pm.getResourcesForApplication(packageName).assets
                val stream = assets.open("appfilter.xml")
                val factory = XmlPullParserFactory.newInstance()
                factory.newPullParser().apply { setInput(stream, "UTF-8") }
            }

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null) {
                        iconMap[component] = drawable
                    }
                }
                eventType = parser.next()
            }
            return true
        } catch (_: Exception) {
            iconPackResources = null
            iconPackPackage = null
            return false
        }
    }

    fun getIconForApp(componentName: ComponentName): Drawable? {
        val res = iconPackResources ?: return null
        val pkg = iconPackPackage ?: return null

        val key = "ComponentInfo{${componentName.packageName}/${componentName.className}}"
        val drawableName = iconMap[key] ?: return null

        return try {
            val id = res.getIdentifier(drawableName, "drawable", pkg)
            if (id != 0) res.getDrawable(id, null) else null
        } catch (_: Exception) {
            null
        }
    }

    fun getIconByName(drawableName: String): Drawable? {
        val res = iconPackResources ?: return null
        val pkg = iconPackPackage ?: return null
        return try {
            val id = res.getIdentifier(drawableName, "drawable", pkg)
            if (id != 0) res.getDrawable(id, null) else null
        } catch (_: Exception) { null }
    }

    fun getAllIconNames(): List<String> {
        return iconMap.values.distinct().sorted()
    }

    fun clearIconPack() {
        iconMap.clear()
        iconPackResources = null
        iconPackPackage = null
        currentPackage = null
    }

    fun isLoaded() = iconPackPackage != null

    fun applyFallbackShape(icon: Drawable, shape: Int, sizePx: Int): Drawable {
        val bitmap = drawableToBitmap(icon, sizePx)
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        val path = Path()
        when (shape) {
            LauncherPrefs.SHAPE_CIRCLE -> {
                val r = sizePx / 2f
                path.addCircle(r, r, r, Path.Direction.CW)
            }
            LauncherPrefs.SHAPE_ROUNDED_SQUARE -> {
                val corner = sizePx * 0.2f
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
            }
            LauncherPrefs.SHAPE_SQUARE -> {
                path.addRect(rect, Path.Direction.CW)
            }
            LauncherPrefs.SHAPE_SQUIRCLE -> {
                val corner = sizePx * 0.28f
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
            }
            else -> {
                path.addRect(rect, Path.Direction.CW)
            }
        }

        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, rect, paint)
        return BitmapDrawable(context.resources, output)
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }
}
