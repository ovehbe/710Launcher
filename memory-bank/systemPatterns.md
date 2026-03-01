# System Patterns

## Architecture
- **Single-Activity launcher:** `LauncherActivity` owns status bar, header, action bar (sound + search + ticker/applets), content (ViewPager2 + NotificationHub overlay), tab bar, dock. Search and notification hub are overlays.
- **State:** All persistent settings in `LauncherPrefs` (SharedPreferences). App list and stats from `AppRepository` + Room `AppStats`. Page order and membership in prefs; filtered by `hideAllPage` / `hideFrequentPage` and `getHiddenApps()`.

## Key technical decisions
- **No global focus on search field when overlay closed:** Search `EditText` is GONE when overlay is dismissed so trackpad doesn’t get stuck and action bar stays tappable. Type-to-search starts via `onKeyDown` and then shows overlay.
- **Click highlights:** Ripple from `LauncherPrefs.getClickHighlightRipple(context)`; `defaultFocusHighlightEnabled = false` on interactive views to avoid stuck focus ring with trackpad. `refreshClickHighlights()` in `onResume()` so accent/color changes apply.
- **Default home tab:** Stored as `defaultTabPageId` (e.g. `"favorites"`) not index, so it survives hiding All/Frequent. `getFilteredPageOrder()` used by `AppPagerAdapter`.
- **Notification applets:** Optional; when enabled, per-app icons + counts in action bar; when disabled, fallback “N Notifications”. Applets hide when ticker is showing; optional auto-hide when count 0.

## Design patterns
- **Callbacks:** `onAppsChanged`, `onNotificationsChanged` used to refresh UI after data changes. Ticker/count and applets should update when notifications change without requiring launcher restart.
- **Prefs:** One `LauncherPrefs(context)`; all keys in one file; fixed keys in `fixedExportKeys` and in `exportToJson()` for backup.
- **Page IDs:** `"frequent"`, `"favorites"`, `"all"`, `"custom_<name>"`; dock uses `"dock"` for icon pack/shortcuts; applets use `"applets"` for icon pack.

## Component relationships
- **LauncherActivity** → statusBar, headerView, appPager (ViewPager2), searchOverlay, notificationHub, dockBar, tabBarContainer, actionBar (ticker/applets, search button, sound). Sets up click highlights, action bar center action, type-to-search, refresh on resume.
- **AppPagerAdapter** → fragments per page (Frequent, Favorites, All, Custom); uses `getFilteredPageOrder()`; `getPositionForPageId` for default tab.
- **AppRepository** → loads apps from PackageManager, applies hidden-app filter, icon packs, sort; syncs favorites with prefs + Room.
- **NotifListenerService** → provides `getNotifications()`, `dismissAllNotifications()`; invokes `onNotificationsChanged` so UI can refresh ticker, count, applets.
- **DockBar, HeaderView, NotificationHub, SearchOverlay** → receive prefs/callbacks from LauncherActivity; no direct cross-reference.
