## Unreleased

- **Free-form grid placement (Favorites & Custom pages)**
  - Favorites and Custom pages in grid view now use an invisible grid — hold any app or shortcut and drop it at any cell, leaving gaps wherever you like, exactly like a traditional home screen launcher.
  - Grid positions are saved per-page; each item has a stable key so positions survive app installs, uninstalls, and relaunches.
  - Existing users are migrated automatically: on first load items are assigned sequential positions (top-left to right) and positions are written the first time anything is dragged.
  - Empty cells act as transparent drop targets (same size as occupied cells) but cannot themselves be picked up and dragged.
  - Long-press without movement still opens the item context menu, as before.
  - Shortcut and intent-shortcut positions are also saved (previously only apps had a saved order).
  - List view mode and All/Frequent pages are unaffected — their layout is unchanged.
  - **Boundary enforcement**: the grid fills the visible RecyclerView area exactly. The row count is measured from the first real layout pass so no cells exist outside the header–tab-bar viewport. Items saved at out-of-bounds positions (e.g. from a previous icon-size setting) are automatically remapped to the nearest in-bounds slot.
  - **Scroll prevention**: `canScrollVertically()` is overridden at the `GridLayoutManager` level for non-scrollable pages, plus `overScrollMode = NEVER` and nested-scroll disabled — the grid cannot shift under any circumstance.
  - **Drag clamping**: the dragged view is clamped within the RecyclerView boundary during the drag gesture. This prevents items from going visually off-screen and ensures `ItemTouchHelper`'s drop-target detection always lands on a real cell, making the top and bottom rows reliably droppable.
  - **Consistent cell heights**: empty cells are sized identically to real cells (icon dimensions + non-breaking-space label) so all rows have uniform height and the row-count measurement is always accurate.

- **Dock — shortcut drag-to-reorder**
  - Shortcuts pinned to the dock can now be held and dragged to any position, just like apps.
  - Introduced a unified dock order (`dock_unified_order`) so apps and shortcuts share a single ordered list; any item can be placed at any slot.
  - Existing dock layouts are automatically migrated on first load (apps first, shortcuts after) with no visible change until the user drags something.
  - Stale entries (e.g. uninstalled apps) are pruned automatically when the dock loads.
  - Fixed: all pin/unpin capacity and duplicate checks now use the unified order as the single source of truth, preventing the legacy `dock_apps` pref from going out of sync and wrongly blocking pins.
  - Fixed: `loadDock()` now bails early if the app list hasn't loaded yet, preventing the unified order from being wiped to empty during startup.

- **Search normalization**
  - Added `SearchNormalizer` to normalize search text across the app (Unicode NFD, strip diacritics, keep letters/digits, lowercase).
  - Search is now diacritic-insensitive so names like `Türkiye Finans` can be found with `turkiye finans`, and similar mappings work for other accented characters.

- **Contact search behavior**
  - Tightened contact name matching so results are limited to names whose normalized form **equals** or **starts with** the normalized query (no loose mid-name matches for long queries).
  - Kept partial phone-number matching so typing digits can still find contacts by number.

- **Dialer interaction**
  - When typing in search, entering a **space** now exits dial mode for that query: no dial digits are generated and number-based search is ignored after a space.

- **Notification hub — overlay focus**
  - Opening the hub via trackpad or touch now consistently focuses the first notification row.
  - Trackpad/D-pad navigation stays trapped inside the hub while it is visible; focus can no longer wander behind the overlay.
  - Focus chain is now circular: moving past the last item wraps back to the first, and vice-versa.
  - Left/right D-pad moves are suppressed inside the hub to prevent accidental escapes.
  - Added activity-level D-pad guard: if focus somehow leaves the hub, the next arrow key snaps it back in.

- **Notification hub — click reliability**
  - Tapping a notification now correctly opens the specific screen set by the app (e.g. a particular chat thread) rather than clearing the notification silently.
  - On Android 14+, `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED` is used so the OS does not silently block the launch.
  - If the notification's `contentIntent` has been cancelled, the app's main activity is opened as a fallback.

- **Action bar — long press action**
  - The notification ticker / action bar area now supports a **long press** action in addition to the existing tap action.
  - Default long press action is **Notification hub** (same as tap default).
  - Fully customizable in Settings under **"Action bar long press"**: choose Notification hub, App, or Shortcut — exactly the same options as the tap action.
  - Setting is included in backup/restore.

- **Sound profile overlay — overlay focus**
  - Same focus-trapping improvements as the notification hub: trackpad navigation stays within the overlay and cycles circularly through the sound mode rows.
  - Fixed the container view stealing focus from the rows on certain API levels.

