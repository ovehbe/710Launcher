package com.meowgi.launcher710.ui.appgrid

import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.ui.widgets.WidgetContainer
import com.meowgi.launcher710.ui.widgets.WidgetHost

class AppGridFragment : Fragment() {

    companion object {
        private const val ARG_TAB = "tab"
        private const val ARG_PAGE_ID = "page_id"
        const val TAB_FREQUENT = 0
        const val TAB_FAVORITES = 1
        const val TAB_ALL = 2
        const val TAB_CUSTOM = 3

        fun newInstance(tab: Int, pageId: String = ""): AppGridFragment {
            return AppGridFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TAB, tab)
                    putString(ARG_PAGE_ID, pageId)
                }
            }
        }
    }

    private var tab = TAB_ALL
    var pageId = ""
        private set
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppAdapter
    var repository: AppRepository? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null
    var onEmptySpaceLongClick: (() -> Unit)? = null
    var widgetHost: WidgetHost? = null

    private var widgetContainer: WidgetContainer? = null

    private val supportsWidgets: Boolean
        get() = tab == TAB_FAVORITES || tab == TAB_CUSTOM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = arguments?.getInt(ARG_TAB, TAB_ALL) ?: TAB_ALL
        pageId = arguments?.getString(ARG_PAGE_ID, "") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val prefs = LauncherPrefs(requireContext())
        val scrollable = prefs.isPageScrollable(pageId)

        val listPages = prefs.getListViewPages()
        val effectiveViewMode = if (listPages.isEmpty()) {
            // Legacy fallback: check old appViewModeAllOnly boolean
            if (prefs.appViewModeAllOnly) {
                if (tab == TAB_ALL) prefs.appViewMode else 0
            } else {
                prefs.appViewMode
            }
        } else if (listPages.contains("__all__")) {
            prefs.appViewMode // Everywhere
        } else {
            if (listPages.contains(pageId)) prefs.appViewMode else 0
        }

        // Determine span count based on view mode
        val spanCount = if (effectiveViewMode == 1) {
            prefs.listViewColumns // List mode uses list columns
        } else {
            prefs.gridColumns // Grid mode uses grid columns
        }

        recycler = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(context, spanCount)
            setBackgroundColor(0)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            if (scrollable) isNestedScrollingEnabled = false
        }

        adapter = AppAdapter(
            requireContext(),
            onClick = { app -> repository?.launchApp(app) },
            onLongClick = { app, view -> onAppLongClick?.invoke(app, view) },
            viewMode = effectiveViewMode,
            iconResolver = if (repository != null) {
                { app -> repository!!.getIconForPage(app, pageId) }
            } else {
                null
            }
        )
        recycler.adapter = adapter
        setupEmptySpaceLongClick()

        if (supportsWidgets) {
            val innerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            widgetContainer = WidgetContainer(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val host = widgetHost
            if (host != null) widgetContainer?.setup(host, pageId)
            innerLayout.addView(widgetContainer)

            if (scrollable) {
                recycler.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                innerLayout.addView(recycler)
                val scrollView = androidx.core.widget.NestedScrollView(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                scrollView.addView(innerLayout)
                return scrollView
            } else {
                innerLayout.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                recycler.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                innerLayout.addView(recycler)
                return innerLayout
            }
        }

        if (scrollable) {
            recycler.isNestedScrollingEnabled = false
            recycler.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val scrollView = androidx.core.widget.NestedScrollView(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            scrollView.addView(recycler)
            return scrollView
        }

        recycler.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return recycler
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized || !::recycler.isInitialized) return
        val prefs = LauncherPrefs(requireContext())
        val listPages = prefs.getListViewPages()
        val effectiveViewMode = if (listPages.isEmpty()) {
            if (prefs.appViewModeAllOnly) {
                if (tab == TAB_ALL) prefs.appViewMode else 0
            } else {
                prefs.appViewMode
            }
        } else if (listPages.contains("__all__")) {
            prefs.appViewMode
        } else {
            if (listPages.contains(pageId)) prefs.appViewMode else 0
        }
        val spanCount = if (effectiveViewMode == 1) prefs.listViewColumns else prefs.gridColumns
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        refreshList()
    }

    fun refreshList() {
        if (!::adapter.isInitialized) return
        val repo = repository ?: return
        val list = when (tab) {
            TAB_FREQUENT -> repo.getFrequentApps()
            TAB_ALL -> repo.getAllApps()
            TAB_FAVORITES -> repo.getFavoriteApps()
            TAB_CUSTOM -> repo.getAppsForPage(pageId)
            else -> emptyList()
        }
        adapter.submitList(list)
    }

    fun addWidgetToPage(widgetId: Int, info: AppWidgetProviderInfo) {
        widgetContainer?.addWidget(widgetId, info)
    }

    private fun setupEmptySpaceLongClick() {
        val detector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    val child = recycler.findChildViewUnder(e.x, e.y)
                    if (child == null) onEmptySpaceLongClick?.invoke()
                }
            })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
