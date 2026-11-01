# System Patterns

## Architecture
- **Single-Activity launcher:** `LauncherActivity` owns status bar, header, action bar (sound + search + ticker/applets), content (ViewPager2 + NotificationHub overlay), tab bar, dock. Search and notification hub are overlays.
- **State:** All persistent settings in `LauncherPrefs` (SharedPreferences). App list and stats from `AppRepository` + Room `AppStats`. Page order and membership in prefs; filtered by `hideAllPage` / `hideFrequentPage` and `getHiddenApps()`.

## Key technical decisions
- **No global focus on search field when overlay closed:** Search `EditText` is GONE when overlay is dismissed so trackpad doesn’t get stuck and action bar stays tappable. Type-to-search starts via `onKeyDown` and then shows overlay.
- **Click highlights:** Ripple from `LauncherPrefs.getClickHighlightRipple(context)`; `defaultFocusHighlightEnabled = false` on interactive views to avoid stuck focus ring with trackpad. `refreshClickHighlights()` in `onResume()` so accent/color changes apply.
- **Default home tab:** Stored as `defaultTabPageId` (e.g. `"favorites"`) not index, so it survives hiding All/Frequent. `getFilteredPageOrder()` used by `AppPagerAdapter`.
- **Web-search fallback flags:** `SearchOverlay.openWebSearchWithQuery` must start with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`. Both halves matter and each has already been got wrong once: without `NEW_TASK` the search app joins the launcher's home task and never appears in Recents; with `RESET_TASK_IF_NEEDED` instead of `CLEAR_TASK` a leftover search task is merely brought forward and the new query is dropped.
- **Notification applets:** Optional; when enabled, per-app icons + counts in action bar; when disabled, fallback “N Notifications”. Applets hide when ticker is showing; optional auto-hide when count 0.

## Search keystroke path (keep it cheap)
- The search `TextWatcher` applies the query **synchronously** when the overlay is visible. No debounce: it was pure latency and let Enter launch an older query's first result. `SearchOverlay.applyQuery` is a no-op for the query already shown.
- Matching is `SearchNormalizer.match` over precomputed fields (`AppInfo.normalizedLabel` / `normalizedWords` / `normalizedPackage`; `IndexedItem` for page-scoped filter lists, built once in `showFilter`). Never normalize or split labels, or compile a regex, per keystroke. `SearchNormalizerTest` pins the matcher to the legacy behaviour.
- Built-in results (Settings, Refresh), the contact icon and the command icon are cached per overlay session (`show`/`showFilter` reset them). Building them touches icon packs, bitmap shaping and PackageManager.
- Contacts: `ContactSearchHelper.searchAsync` runs on a single background worker, latest request only (generation counter). The phone table is indexed once per session (`invalidateCache`). Still-matching contacts carry over between keystrokes so they don't blink.
- Results go through `AppAdapter.submitListDiffed` (synchronous DiffUtil, no stable ids because contact keys aren't unique). The results RecyclerView has `setHasFixedSize(true)` and no item animator.

## Search trackpad navigation (DPAD / trackball)
- Keyboard focus stays on `searchInputInBar` while search is open. Plain focus search must not be used there: from the action-bar field, FOCUS_DOWN finds the home pager's RecyclerView behind the overlay, and typed keys are dropped once focus is there.
- The overlay owns a virtual selection (`navPos`) over rows in on-screen order: the dial row, Google suggestion rows, result grid rows (adapter positions chunked by span count), and the AI "Open AI settings" link. The grid wraps left/right (`SearchNavigation.move`, pure and unit-tested). The highlight is drawn in `SearchOverlay.dispatchDraw`, not with view focus states. It is a soft neutral fill with 6dp corners and no outline or accent (device feedback: the accent outline was too loud). `navHighlightColor` uses white at 0x1F, or black at 0x1A when the overlay's own background is light and mostly opaque.
- `LauncherActivity.handleSearchKey` (from `dispatchKeyEvent`) routes DPAD keys to `navigate`/`activateNavSelection`. The UP of every consumed DOWN is consumed too (`searchNavKeys`).
- **Every Enter-like submit goes through `submitSearch`** (Alt+Enter asks AI, otherwise the highlighted row opens, otherwise the query runs). Never add a submit path that skips it. An IME sees hardware keys before the activity and may deliver Enter as `performEditorAction` (editor-action listener only) or as a committed newline (InputFilter), so `Activity.dispatchKeyEvent` is not the only entry. Tests must cover the field and IME paths too (`SearchEnterPathsTest`). Typing keys clear the selection and refocus the field.
- Trackball: `dispatchTrackballEvent` consumes everything while search is shown, so the system doesn't also synthesize DPAD keys. `TrackballNavigator` steps once per 1.0 of accumulated motion (the MotionEvent trackball unit is one DPAD press).
- Opening search must start fresh: `resetSession()` resets the results grid and AI panel scroll and clears the highlight. The overlay view persists between sessions, so new per-session view state belongs there too.
- Any change to what is shown (`publish`, suggestions shown or cleared, dial row change, `renderAi`, session reset, `dismiss`) must call `clearNavSelection()`.
- Robolectric's `Activity.dispatchKeyEvent` skips ViewRootImpl focus navigation, so tests read focus with `decorView.findFocus()` (`currentFocus` is null) and use `focusSearch` to model where focus would go.

## AI answers (`ai/`)
- `AiProtocol` (SSE parser, request/response JSON, error mapping) and `AiMarkdown.parse` are pure Kotlin and unit-tested. `AiClient` does blocking HttpURLConnection calls. `AiAnswerSession` runs one answer at a time on the main thread: a generation counter drops anything from a cancelled or replaced request, and tokens are coalesced into one posted update.
- `SearchOverlay` owns the session. `applyQuery` (on a changed query), `resetSession` and `dismiss` cancel it. Web suggestions are blocked while it is active. Test seams: `aiConfigProvider` and `aiSession.streamer`.
- Never log the API key or put it in `LauncherPrefs`. It lives only in `AiSecrets` (the `ai_secrets` prefs file). Failure messages pass through `AiProtocol.redact`, and `AiConfig.toString` masks it. Any new backup path must skip `BackupEngine.EXCLUDED_PREF_FILES`.
- To open Settings on a given screen, use `SettingsActivity.intent(context, SettingsScreen.X)`. It adds CLEAR_TOP|SINGLE_TOP and an extra, and is handled in `onCreate`/`onNewIntent`.

## Stability invariants (do not regress)
- **Attach the pager synchronously.** `setupPager()` must run inside `onCreate`, never behind an `await` on `loadApps()`. ViewPager2 replays restored state into the adapter at attach time; deferring it caused the `Fragment no longer exists for key f#N` crashes.
- **No state restore on the home screen.** `appPager.isSaveEnabled = false` and fragment state is stripped from `savedInstanceState`. Don't reintroduce either.
- **Fragment identity is the page ID.** `AppPagerAdapter.getItemId`/`containsItem` hash the page ID, because positions shift when All/Frequent are hidden.
- **Never hold fragments in a map.** Resolve through `FragmentManager.findFragmentByTag("f$itemId")`; a local map leaks destroyed fragments and goes stale on reorder.
- **Icons come from the cache.** `getIconForPage` is called per RecyclerView bind — it must hit `AppRepository`'s LruCache, not re-render. Call `clearIconCache()` whenever packs, custom icons, or shapes change.
- **`onResume` stays cheap.** No full `loadApps()` and no icon-pack parsing unless the pack signature actually changed; use `refreshStats()` for the common case.
- **`AppRepository` is process-wide.** Get it with `AppRepository.get(context)` (application context); never construct one per activity. Density/resolution/font/locale changes recreate `LauncherActivity`, and a fresh repository is what made icons vanish for 2–3 s while a full scan ran. The recreated activity must render the existing `apps` synchronously and call `reloadInBackground()` only when `needsReload()` (cold start, density changed since icons were decoded, or `invalidate()`d). A reload never clears `apps` first.
- **Icon packs live in the repository.** `onCreate` re-parses a pack only when its prefs assignment differs from the manager's `currentPackage` (`syncIconPack`) or the page-pack signature changed. Explicit `recreate()` paths call `repository.invalidate()` first (Settings return) and also `forgetIconPacks()` for "Refresh launcher".
- **Activity attach is identity-checked.** `repository.attach(activity, onAppsChanged)` in `onCreate`, `repository.detach(this)` in `onDestroy`; `detach` ignores a stale activity so it can't null the callback of its replacement.
- **Coroutines are scoped.** `lifecycleScope` in the activity, the process-lifetime `appScope` in `AppRepository`. No ad-hoc `CoroutineScope(...)`.
- **Static callbacks are paired.** `NotifListenerService.onNotificationsChanged` is set in `onStart` and cleared in `onStop`; it holds the activity.

## Settings structure
- `SettingsActivity` runs in its own task (manifest `taskAffinity`), so it shows in Recents. In the launcher's singleTask home task, Home would clear it. Always start it with `SettingsActivity.intent(context)`; `SettingsTaskTest` fails on any other start site. It can't return results to the launcher.
- `SettingsActivity` is only the host: launchers, navigation (`showScreen`, Back returns to the category list), `rebuildSettings()` (re-renders the current screen, keeps scroll). Toolbar is outside the rebuilt container.
- Categories are `SettingsScreen` in `SettingsScreens.kt`, one `build*()` per category; add a new setting as a row inside the right `group(...)` there. Row widgets (`toggleRow`, `choiceRow`, `colorChoiceRow`, `sliderRow`, `numberRow`, `actionRow`…) are in `SettingsRows.kt`; dialogs in `Settings*Dialogs.kt`; the four tap-action slots in `SettingsTapActions.kt`.
- `app/src/test/resources/settings_pref_baseline.txt` is the snapshot of prefs/entry points the old screen exposed; `SettingsPrefCoverageTest` fails if one stops being reachable or a key changes. Don't edit the baseline to make it pass; add to `renamedActions` only for a deliberate replacement.

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
