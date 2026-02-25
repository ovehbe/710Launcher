package com.meowgi.launcher710.ui.appgrid

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.ShortcutHelper
import com.meowgi.launcher710.ui.widgets.WidgetHost

class AppPagerAdapter(
    private val activity: FragmentActivity,
    private val repository: AppRepository,
    private val shortcutHelper: ShortcutHelper,
    private val widgetHost: WidgetHost,
    private val onItemLongClick: (LaunchableItem, android.view.View) -> Unit,
    private val onEmptySpaceLongClick: () -> Unit
) : FragmentStateAdapter(activity) {

    private val fragments = mutableMapOf<Int, AppGridFragment>()
    private val prefs = LauncherPrefs(activity)
    private var pageOrder = prefs.getPageOrder()

    override fun getItemCount() = pageOrder.size

    override fun createFragment(position: Int): Fragment {
        val pageId = pageOrder[position]
        val tabType = when (pageId) {
            "frequent" -> AppGridFragment.TAB_FREQUENT
            "favorites" -> AppGridFragment.TAB_FAVORITES
            "all" -> AppGridFragment.TAB_ALL
            else -> AppGridFragment.TAB_CUSTOM
        }
        val frag = AppGridFragment.newInstance(tabType, pageId)
        frag.repository = repository
        frag.shortcutHelper = shortcutHelper
        frag.onItemLongClick = onItemLongClick
        frag.onEmptySpaceLongClick = onEmptySpaceLongClick
        frag.widgetHost = widgetHost
        fragments[position] = frag
        return frag
    }

    fun refreshAll() {
        fragments.values.forEach { it.refreshList() }
    }

    fun getFragment(position: Int) = fragments[position]

    fun getPageId(position: Int): String = pageOrder.getOrElse(position) { "favorites" }

    fun getPageName(position: Int): String {
        return when (pageOrder.getOrElse(position) { "" }) {
            "frequent" -> "Frequent"
            "favorites" -> "Favorites"
            "all" -> "All"
            else -> pageOrder[position].removePrefix("custom_")
        }
    }

    fun reloadPageOrder() {
        pageOrder = prefs.getPageOrder()
    }

    fun getPositionForPageId(pageId: String): Int = pageOrder.indexOf(pageId).takeIf { it >= 0 } ?: 0
}
