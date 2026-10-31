# Progress

## What works
- Launcher as default home; status bar, header, action bar, tab bar, dock, app grid/list (Frequent, Favorites, All, custom pages).
- Hide All/Frequent; default home tab by page ID; hidden apps; sorting and sort scope.
- Search (fuzzy, type-to-search from home, Alt/Shift symbols); search overlay; optional external search modes.
- Notifications: ticker (typing animation), hub (list, filters, Clear All), notification applets (per-app icons + counts) or fallback “N Notifications”.
- Header and action bar center tap actions (app/shortcut/hub); click highlights (ripple, configurable color/opacity, no focus ring).
- Icon packs (per-page, dock, applets); icon picker search; global/fallback icon shaping.
- Dock: pinned apps, intent shortcuts, swipe-up actions, reorder, custom color.
- Tab bar: highlight color and opacity; double-tap action (lock screen / hub).
- Settings: full UI; export/import; Restart Launcher intent.
- Widgets on supported pages; key shortcuts (accessibility) optional.

## What’s left / in progress
- **Ticker/count refresh:** Ticker and count sometimes don’t update after clearing notifications until launcher restart; ensure `onNotificationsChanged` and related refresh paths update all relevant UI.
- **Page visibility:** Frequent/All “hidden” state should be persistent (no random reappearance when opening menu or after actions).
- **Applet icons:** Display quality and custom icon handling for notification applets (pixelation/custom icons).
- **General reliability:** Reduce “need to restart” for settings/view changes; document and then address restart-heavy behavior.
- **Planned (from backlog):** Call/contact search; notification hub items selectable (and trackpad selection for hub).

## Current status
- Two search fixes shipped in v1.5.2: the empty-search Google fallback launches in its own task so it shows up in Recents (it was inheriting the launcher's home task, which Android keeps out of Recents), and app search matches package names via a new pre-computed `AppInfo.normalizedPackage`. Package-only matches rank after name matches. Both the repository search and `SearchOverlay`'s page-scoped `filterItems` path needed the package change — searching from All/Frequent never reaches `AppRepository.searchApps`.
- v1.5.3 follow-up: the fallback flags are now `NEW_TASK | CLEAR_TASK`. `RESET_TASK_IF_NEEDED` only brought an existing search task forward, so a second fallback search reused the stale task and dropped the new query. `CLEAR_TASK` finishes that task first, which still yields a normal Recents entry.
- v1.5.4: resolution / display-size changes no longer blank the app icons for 2–3 s; the process-wide `AppRepository` keeps the list, packs and icon cache across the recreate (see `activeContext.md`). On-device verification pending (cloud emulator could not boot).
- v1.5.5: typing in the built-in search is instant. Removed the 80 ms debounce; the matcher is about 40× cheaper (precomputed words, no per-call regex); built-ins and icons are cached per session; contact lookups are latest-only on one worker with a per-session phone index; results are diffed instead of fully rebound. See `systemPatterns.md` → "Search keystroke path". On-device verification pending.
- Dialer fix (unreleased): the dial row and Enter use `DialNumber.dialable(query)`, which accepts only digits, `*`, `#`, one leading `+` and the separators space, NBSP, `-`, `(`, `)`, `.`; any letter hides the row. Enter dials a dialable query before launching the first result or falling back to web search, except in command mode. Letter-to-keypad conversion (`convertToDialDigits`) still feeds app-label and contact digit matching only.
- Google suggestions (unreleased): when no result matches the query as text and the query is not dialable, `SearchOverlay` asks `util/WebSuggestions` (suggestqueries.google.com, `client=firefox`) for up to 6 suggestions. The request is debounced by 200 ms and latest-only: a new query or a text match cancels the pending job and abort the in-flight HTTP. Offline or malformed responses show nothing. Rows live in their own `suggestionsBox`, outside the results adapter; tapping one runs `openWebSearchWithQuery` (fresh task). It is off in command mode and @-contact mode. Pref `searchWebSuggestions` (default on) is read once per session. Added the `INTERNET` permission.
- Search label removal (#14) was reverted in 1.6.0 at the user's request: the "Extended / All Apps / Frequents" label is back.
- Swipe down for notifications (unreleased): `LauncherActivity.handleSwipeDown` runs first in `dispatchTouchEvent`, and the decision logic lives in the pure `util/SwipeDownGesture`. A swipe can start only with no overlay open, and only if no view under the finger `canScrollVertically(-1)` or is a `ResizableWidgetFrame` in resize mode. Direction locks once the finger leaves the slop: horizontal or upward motion is rejected, and so is a finger held still past the long-press timeout (the start of a drag). It triggers at 64dp with dy ≥ 2·|dx|, or on a fast flick at UP. Children then get CANCEL and the rest of the stream is swallowed. Expansion order: `NotificationShade.expand` (reflection on `StatusBarManager.expandNotificationsPanel`, `EXPAND_STATUS_BAR`), then accessibility `GLOBAL_ACTION_NOTIFICATIONS`, then the in-app hub. Pref `swipeDownNotifications` (default on), toggled in Settings → Behavior.
- Usage-based search ranking (unreleased): `util/SearchRanker` grades matches into tiers (exact label, label prefix, later-word prefix, other name match such as substring, prefix-per-word or keypad digits, package only). Within a tier it orders by frecency, then label. Frecency is the launch score with a 7-day half-life, `bump = decay + 1`, compared via the time-invariant `rankKey = log2(score) + at/HALF_LIFE`. `AppRepository.usageKeys` caches those keys (rebuilt on stats load, patched on launch), so a keystroke never touches the DB. Room v2 adds `frecency`/`frecencyAt` through `MIGRATION_1_2`, seeded from `launchCount`/`lastLaunched`. `recordLaunch` and `toggleFavorite` share `statsWriteMutex`. Pref `searchUsageRanking` (default on; off means tier then alphabetical). Settings → Search has "Reset usage data" with a confirm: it zeroes counts, times and frecency and keeps favourites.
- Settings overhaul (branch `cursor/settings-overhaul-b470`): the flat scroll is now a category home list with sub-screens of grouped cards; every row shows title + current-value summary. Same pref keys; `SettingsPrefCoverageTest` checks every pre-overhaul pref and entry point is still reachable. See `systemPatterns.md` → "Settings structure". On-device verification pending.
- Backup & restore (branch `cursor/backup-restore-b470`): one versioned zip (manifest.json with per-entry SHA-256, plus prefs/, db/, assets/). It dumps every shared_prefs file, every DB table, and every data-dir file a pref points at. A restore is validated in full before any write, staged, journaled with a snapshot, and rolled back on failure or on the next launch (`backup/`). Old settings JSON exports are migrated as schema 1. On-device verification pending.
- App version 1.6.0 (versionCode 20): integration of #11–#18 on `cursor/integration-v1.6.0`. Device-test fixes in versionCode 20:
  - The dial row accepts numbers typed as QWERTY keypad letters (w e r s d f z x c = 1–9, a = `*`, q = `#`, o = `+`; `DialNumber.keypadChar`), because the device keyboard delivers number keys as letters without Alt.
  - Google suggestions are suppressed only by text matches, not by keypad-digit matches (`WebSuggestions.shouldRequest`).
  - The Extended label is back.
  - Settings pads itself for the system bars: it clears the theme's translucent status flag and applies insets on `settingsRoot`/`settingsToolbar`.
  - Robolectric tests (`SearchOverlayDecisionTest`, `SettingsInsetsTest`) drive the real overlay and activities.
- Stability pass (Phases 1–3) implemented and compiling; on-device verification on the Q25 still pending.
- Memory bank initialized; `.cursor/rules` and `memory-bank/` should be committed and pushed so pull brings full context.

## Known issues
- **Fixed, pending device verification:** crash on restore (`Fragment no longer exists for key f#N`) and `lateinit pagerAdapter` crash; launcher being killed in the background from icon-bitmap churn and per-resume full re-index. See `activeContext.md` for the root cause and the changes.
- Ticker and count can stay visible or stale after clearing notifications until launcher restart.
- Hiding Frequent or All can appear inconsistent (initially still there, after menu gone, etc.); should be persistently hidden.
- Notification applet icons can look pixelated or worse with custom icons.
- Some features (e.g. grid/list switch) may require launcher restart to apply fully.
- Notification hub: individual items not selectable; trackpad selection only highlights hub, not items (to be addressed later).
