package com.meowgi.launcher710.ui.appgrid

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentOnAttachListener
import androidx.recyclerview.widget.RecyclerView
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

    private val prefs = LauncherPrefs(activity)
    private var pageOrder = filterPageOrder(prefs.getPageOrder())

    /**
     * Injects dependencies into every [AppGridFragment] that attaches, not just the ones this
     * adapter creates. Fragments restored by the FragmentManager skip [createFragment] entirely,
     * and without this they would come back with a null repository and render blank pages.
     */
    private val attachListener = FragmentOnAttachListener { _, fragment ->
        if (fragment is AppGridFragment) bind(fragment)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        activity.supportFragmentManager.addFragmentOnAttachListener(attachListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        activity.supportFragmentManager.removeFragmentOnAttachListener(attachListener)
    }

    private fun filterPageOrder(order: List<String>): List<String> =
        order.filter { pid -> if (pid == "all") !prefs.hideAllPage else if (pid == "frequent") !prefs.hideFrequentPage else true }

    override fun getItemCount() = pageOrder.size

    /**
     * Item identity is the page ID, not the list position. Hiding All/Frequent or reordering pages
     * shifts positions, and a position-based ID would make ViewPager2 associate a restored fragment
     * with the wrong page — or with a page that no longer exists.
     */
    override fun getItemId(position: Int): Long =
        pageOrder.getOrElse(position) { "" }.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean =
        pageOrder.any { it.hashCode().toLong() == itemId }

    private fun bind(frag: AppGridFragment) {
        frag.repository = repository
        frag.shortcutHelper = shortcutHelper
        frag.onItemLongClick = onItemLongClick
        frag.onEmptySpaceLongClick = onEmptySpaceLongClick
        frag.widgetHost = widgetHost
    }

    override fun createFragment(position: Int): Fragment {
        val pageId = pageOrder[position]
        val tabType = when (pageId) {
            "frequent" -> AppGridFragment.TAB_FREQUENT
            "favorites" -> AppGridFragment.TAB_FAVORITES
            "all" -> AppGridFragment.TAB_ALL
            else -> AppGridFragment.TAB_CUSTOM
        }
        return AppGridFragment.newInstance(tabType, pageId).also { bind(it) }
    }

    fun refreshAll() {
        for (i in 0 until itemCount) {
            getFragment(i)?.takeIf { it.isAdded }?.refreshList()
        }
    }

    /**
     * Resolves the live fragment through the FragmentManager using the tag FragmentStateAdapter
     * assigns ("f" + itemId). Keeping our own map would hold strong references to fragments the
     * adapter has already destroyed, and would go stale as soon as the page order changed.
     */
    fun getFragment(position: Int): AppGridFragment? {
        if (position < 0 || position >= pageOrder.size) return null
        return findFragment(activity.supportFragmentManager, getItemId(position))
    }

    private fun findFragment(fm: FragmentManager, itemId: Long): AppGridFragment? =
        fm.findFragmentByTag("f$itemId") as? AppGridFragment

    fun getPageId(position: Int): String = pageOrder.getOrElse(position) { "favorites" }

    fun getPageName(position: Int): String {
        return when (val pageId = pageOrder.getOrElse(position) { "" }) {
            "frequent" -> "Frequent"
            "favorites" -> "Favorites"
            "all" -> "All"
            else -> pageId.removePrefix("custom_")
        }
    }

    /** @return true if the visible page set actually changed, so callers can skip needless rebuilds. */
    fun reloadPageOrder(): Boolean {
        val updated = filterPageOrder(prefs.getPageOrder())
        if (updated == pageOrder) return false
        pageOrder = updated
        return true
    }

    fun getPositionForPageId(pageId: String): Int = pageOrder.indexOf(pageId).takeIf { it >= 0 } ?: 0
}
