package com.meowgi.launcher710.ui.appgrid

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.meowgi.launcher710.R
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.model.LaunchableItem
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.ShortcutHelper
import com.meowgi.launcher710.ui.settings.SettingsActivity
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
    var onEmptySpaceLongClick: (() -> Unit)? = null
    var widgetHost: WidgetHost? = null

    private var widgetContainer: WidgetContainer? = null

    var shortcutHelper: ShortcutHelper? = null
    var onItemLongClick: ((LaunchableItem, View) -> Unit)? = null

    /** True when Favorites/Custom page is displayed in grid (not list) mode — enables sparse grid placement. */
    private var isSparseGrid = false
    /** Current column count; kept in sync with the GridLayoutManager span. */
    private var currentColumns = 6
    /** True when the page has scrolling enabled (wraps RecyclerView in NestedScrollView). */
    private var isPageScrollEnabled = false
    /**
     * Maximum number of rows the grid is allowed to have. For non-scrollable pages this is set
     * precisely from the first measured layout so the grid fills the visible area exactly —
     * no cells above the header, no cells below the tab bar. Default of 6 is replaced after
     * the first layout pass via the OnLayoutChangeListener in onCreateView.
     */
    private var maxGridRows = 6
    /** Becomes true once maxGridRows has been measured from a real layout; prevents re-measuring. */
    private var gridRowsMeasured = false

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

        isSparseGrid = (tab == TAB_FAVORITES || tab == TAB_CUSTOM) && effectiveViewMode != 1
        currentColumns = spanCount
        isPageScrollEnabled = scrollable
        gridRowsMeasured = false

        recycler = RecyclerView(requireContext()).apply {
            // For non-scrollable pages, override canScrollVertically at the LayoutManager level.
            // RecyclerView.scrollBy() checks this gate before doing anything, so neither touch
            // events nor ItemTouchHelper's drag auto-scroll can shift the view.
            layoutManager = if (!scrollable) {
                object : GridLayoutManager(context, spanCount) {
                    override fun canScrollVertically() = false
                }
            } else {
                GridLayoutManager(context, spanCount)
            }
            setBackgroundColor(0)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            if (scrollable) isNestedScrollingEnabled = false
            // Belt-and-suspenders: kill overscroll bounce and nested-scroll propagation too.
            if (!scrollable) {
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
            }
        }

        adapter = AppAdapter(
            requireContext(),
            onClick = { item ->
                when (item) {
                    is LaunchableItem.App -> repository?.launchApp(item.app)
                    is LaunchableItem.Shortcut -> shortcutHelper?.launchShortcut(item.shortcut.packageName, item.shortcut.shortcutId)
                    is LaunchableItem.IntentShortcut -> shortcutHelper?.launchIntentShortcut(item.info.intentUri)
                    is LaunchableItem.LauncherSettings -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    is LaunchableItem.Contact -> { }
                    is LaunchableItem.SearchCommand -> { }
                }
            },
            onLongClick = if (tab == TAB_FAVORITES) null else { item, view -> onItemLongClick?.invoke(item, view) },
            viewMode = effectiveViewMode,
            iconResolver = if (repository != null) {
                { item ->
                    when (item) {
                        is LaunchableItem.App -> repository!!.getIconForPage(item.app, pageId)
                        is LaunchableItem.Shortcut -> repository!!.getIconForShortcut(item.shortcut, pageId)
                        is LaunchableItem.IntentShortcut -> repository!!.getIconForIntentShortcut(item.info, pageId)
                        is LaunchableItem.LauncherSettings -> item.icon
                        is LaunchableItem.Contact -> item.icon
                        is LaunchableItem.SearchCommand -> item.icon
                    }
                }
            } else { null },
            labelResolver = if (repository != null) {
                val prefs = LauncherPrefs(requireContext())
                ({ item: LaunchableItem ->
                    when (item) {
                        is LaunchableItem.App -> repository!!.getDisplayLabel(item.app, pageId)
                        is LaunchableItem.Shortcut -> prefs.getCustomLabel(item.shortcut.shortcutKey, pageId) ?: item.shortcut.label
                        is LaunchableItem.IntentShortcut -> prefs.getCustomLabel(item.info.shortcutKey, pageId) ?: item.info.label
                        is LaunchableItem.LauncherSettings -> item.label
                        is LaunchableItem.Contact -> item.displayName
                        is LaunchableItem.SearchCommand -> item.label
                    }
                })
            } else null,
            isSparseMode = isSparseGrid
        )
        recycler.adapter = adapter

        // For non-scrollable sparse pages: measure the exact number of rows that fit on screen
        // after the first layout pass, then rebuild so the grid fills the visible area precisely.
        // We never estimate — we wait until at least one child is actually laid out so the
        // measurement is always from real pixel dimensions, not guesses.
        if (isSparseGrid && !scrollable) {
            recycler.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    if (gridRowsMeasured) { recycler.removeOnLayoutChangeListener(this); return }
                    val rvH = (b - t - recycler.paddingTop - recycler.paddingBottom).takeIf { it > 0 } ?: return
                    // Use the first child's measured height. With empty cells now sized the same as
                    // real cells (icon + label dimensions set in AppAdapter), any child gives the
                    // correct row height. If there are no children yet, return and wait.
                    val cellH = recycler.getChildAt(0)?.height?.takeIf { it > 0 } ?: return
                    val measured = maxOf(1, rvH / cellH)
                    gridRowsMeasured = true
                    recycler.removeOnLayoutChangeListener(this)
                    if (measured != maxGridRows) {
                        maxGridRows = measured
                        recycler.post { if (isAdded) refreshList() }
                    }
                }
            })
        }

        setupEmptySpaceLongClick()
        if (tab == TAB_FAVORITES || tab == TAB_CUSTOM) {
            if (isSparseGrid) setupGridDragToReorder(recycler)
            else setupDragToReorder(recycler)
        }

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
            val widgetsBelow = prefs.isPageWidgetsBelowApps(pageId)
            if (widgetsBelow) {
                innerLayout.addView(recycler)
                innerLayout.addView(widgetContainer)
            } else {
                innerLayout.addView(widgetContainer)
                innerLayout.addView(recycler)
            }

            if (scrollable) {
                recycler.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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
        isSparseGrid = (tab == TAB_FAVORITES || tab == TAB_CUSTOM) && effectiveViewMode != 1
        currentColumns = spanCount
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        refreshList()
    }

    fun getCurrentItems(): List<LaunchableItem> =
        if (::adapter.isInitialized) adapter.getCurrentList() else emptyList()

    fun refreshList() {
        if (!::adapter.isInitialized) return
        val repo = repository ?: return
        val appList = when (tab) {
            TAB_FREQUENT -> repo.getFrequentApps()
            TAB_ALL -> repo.getAllApps()
            TAB_FAVORITES -> repo.getFavoriteApps()
            TAB_CUSTOM -> repo.getAppsForPage(pageId)
            else -> emptyList()
        }
        val items = appList.map { LaunchableItem.App(it) }.toMutableList<LaunchableItem>()
        if (supportsWidgets) {
            val prefs = LauncherPrefs(requireContext())
            shortcutHelper?.getShortcutsForPage(pageId, prefs)?.forEach { items.add(LaunchableItem.Shortcut(it)) }
            shortcutHelper?.getIntentShortcutsForPage(pageId, prefs)?.forEach { items.add(LaunchableItem.IntentShortcut(it)) }
        }
        if (tab == TAB_ALL) {
            repository?.createLauncherSettingsItem()?.let { items.add(it) }
        }
        if (isSparseGrid) {
            val prefs = LauncherPrefs(requireContext())
            val positions = prefs.getPageGridPositions(pageId)
            adapter.submitSparseList(buildSparseList(items, positions, currentColumns))
        } else {
            adapter.submitList(items)
        }
    }

    fun addWidgetToPage(widgetId: Int, info: AppWidgetProviderInfo) {
        widgetContainer?.addWidget(widgetId, info)
    }

    /**
     * Builds a sparse list for grid placement.
     *
     * Non-scrollable pages: the grid is always exactly [maxGridRows] rows tall — precisely the
     * number of rows that fit in the visible RecyclerView. This is the hard boundary: no cells
     * exist outside the visible area so items can never be placed off-screen. Saved positions
     * that fall outside the bound are re-homed to the next free in-bounds slot.
     *
     * Scrollable pages: dynamic size (items + 2 extra empty rows) so items can be appended.
     */
    private fun buildSparseList(
        items: List<LaunchableItem>,
        positions: Map<String, Int>,
        columns: Int
    ): List<LaunchableItem?> {
        val cols = columns.coerceAtLeast(1)

        val numCells: Int = if (!isPageScrollEnabled) {
            // Non-scrollable: fixed grid filling the visible area exactly.
            maxGridRows * cols
        } else {
            // Scrollable: dynamic size with 2 extra empty rows for appending.
            if (items.isEmpty()) return List(cols * 2) { null }
            val tmpPositioned = mutableMapOf<Int, LaunchableItem>()
            for (item in items) {
                val pos = positions[AppAdapter.itemKey(item)] ?: continue
                tmpPositioned[pos] = item
            }
            var nf = 0
            for (item in items) {
                if (positions.containsKey(AppAdapter.itemKey(item))) continue
                while (tmpPositioned.containsKey(nf)) nf++
                tmpPositioned[nf] = item; nf++
            }
            val mp = tmpPositioned.keys.maxOrNull() ?: -1
            (((mp + 1 + cols - 1) / cols).coerceAtLeast(1) + 2) * cols
        }

        val positioned = mutableMapOf<Int, LaunchableItem>()
        val placedKeys = mutableSetOf<String>()

        // Place items that have a saved position, clamped strictly within the grid boundary.
        for (item in items) {
            val key = AppAdapter.itemKey(item)
            val pos = positions[key] ?: continue
            if (pos < numCells) {
                positioned[pos] = item
                placedKeys.add(key)
            }
        }

        // Items without a position (new, migrated, or remapped from out-of-bounds) placed
        // sequentially in the first available in-bounds cell.
        var nextFree = 0
        for (item in items) {
            val key = AppAdapter.itemKey(item)
            if (key in placedKeys) continue
            while (positioned.containsKey(nextFree)) nextFree++
            if (nextFree >= numCells) break // grid full — shouldn't happen in normal use
            positioned[nextFree] = item
            placedKeys.add(key)
            nextFree++
        }

        return List(numCells) { pos -> positioned[pos] }
    }

    /**
     * Drag-to-reorder for sparse grid mode (Favorites / Custom pages in grid view).
     * Items swap cell positions on drop; empty cells act as drop targets but cannot be picked up.
     * On drag end, the updated grid positions are persisted and the list is rebuilt.
     */
    private fun setupGridDragToReorder(recycler: RecyclerView) {
        var dragStartPosition = -1
        var didMove = false
        var contextMenuShownForThisDrag = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                // Empty cells cannot be dragged — only dropped onto.
                if (vh is AppAdapter.EmptyVH) return makeMovementFlags(0, 0)
                return super.getMovementFlags(rv, vh)
            }
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                didMove = true
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    dragStartPosition = viewHolder.bindingAdapterPosition
                    didMove = false
                    contextMenuShownForThisDrag = false
                }
            }
            // Clamp the dragged view so it never leaves the RecyclerView boundary.
            // This serves two purposes:
            //   1. Visual: the item stays within the grid — no "out of bounds" appearance.
            //   2. Targeting: ItemTouchHelper finds its drop target under the *center* of the
            //      dragged view. Clamping ensures that center always points at a real cell, so
            //      the top row and bottom row are reliably droppable even when the finger strays
            //      past the edge.
            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val iv = viewHolder.itemView
                    val clampedDX = dX.coerceIn(
                        (recyclerView.paddingLeft - iv.left).toFloat(),
                        (recyclerView.width - recyclerView.paddingRight - iv.right).toFloat()
                    )
                    val clampedDY = dY.coerceIn(
                        (recyclerView.paddingTop - iv.top).toFloat(),
                        (recyclerView.height - recyclerView.paddingBottom - iv.bottom).toFloat()
                    )
                    super.onChildDraw(c, recyclerView, viewHolder, clampedDX, clampedDY, actionState, isCurrentlyActive)
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
            // Disable edge auto-scroll as a secondary guard (canScrollVertically=false in the
            // LayoutManager is the primary gate, but this eliminates any residual velocity).
            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long
            ): Int = 0
            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                val sparseList = adapter.getSparseList()
                // Persist the new cell positions for every real item.
                val prefs = LauncherPrefs(requireContext())
                val positions = mutableMapOf<String, Int>()
                for (i in sparseList.indices) {
                    val item = sparseList[i] ?: continue
                    positions[AppAdapter.itemKey(item)] = i
                }
                prefs.setPageGridPositions(pageId, positions)
                // Trigger context menu if this was a long press without any drag movement.
                if (!didMove && !contextMenuShownForThisDrag &&
                    dragStartPosition >= 0 && dragStartPosition < sparseList.size &&
                    sparseList[dragStartPosition] != null
                ) {
                    contextMenuShownForThisDrag = true
                    onItemLongClick?.invoke(sparseList[dragStartPosition]!!, viewHolder.itemView)
                }
                dragStartPosition = -1
                // Defer the adapter rebuild until after ItemTouchHelper finishes its own cleanup
                // on this frame. Calling notifyDataSetChanged() synchronously here races with the
                // settlement animation and causes a visual snap-back.
                recycler.post { if (isAdded) refreshList() }
            }
        }).attachToRecyclerView(recycler)
    }

    private fun setupEmptySpaceLongClick() {
        val detector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    val child = recycler.findChildViewUnder(e.x, e.y)
                    // Treat invisible empty-grid cells the same as bare empty space — in sparse
                    // grid mode every pixel is covered by a child view, so checking for null alone
                    // would never fire. EmptyVH cells are visually indistinguishable from empty
                    // space and should open the page/settings menu on long press.
                    val isEmpty = child == null ||
                        recycler.getChildViewHolder(child) is AppAdapter.EmptyVH
                    if (isEmpty) onEmptySpaceLongClick?.invoke()
                }
            })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun setupDragToReorder(recycler: RecyclerView) {
        var dragStartPosition = -1
        var didMove = false
        var contextMenuShownForThisDrag = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                didMove = true
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    dragStartPosition = viewHolder.bindingAdapterPosition
                    didMove = false
                    contextMenuShownForThisDrag = false
                }
            }
            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                val list = adapter.getCurrentList()
                val componentNames = list.mapNotNull { (it as? LaunchableItem.App)?.app?.componentName?.flattenToString() }
                if (componentNames.isNotEmpty()) {
                    val prefs = LauncherPrefs(requireContext())
                    if (tab == TAB_FAVORITES) prefs.setFavoriteOrder(componentNames)
                    else if (tab == TAB_CUSTOM) prefs.setPageAppOrder(pageId, componentNames)
                }
                if (!didMove && !contextMenuShownForThisDrag && dragStartPosition >= 0 && dragStartPosition < list.size) {
                    contextMenuShownForThisDrag = true
                    onItemLongClick?.invoke(list[dragStartPosition], viewHolder.itemView)
                }
                dragStartPosition = -1
            }
        }).attachToRecyclerView(recycler)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
