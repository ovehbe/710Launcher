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
- App version 1.4.1 (versionCode 5). Dev branch at tag v1.4.2. Main branch stable.
- Memory bank initialized; `.cursor/rules` and `memory-bank/` should be committed and pushed so pull brings full context.

## Known issues
- Ticker and count can stay visible or stale after clearing notifications until launcher restart.
- Hiding Frequent or All can appear inconsistent (initially still there, after menu gone, etc.); should be persistently hidden.
- Notification applet icons can look pixelated or worse with custom icons.
- Some features (e.g. grid/list switch) may require launcher restart to apply fully.
- Notification hub: individual items not selectable; trackpad selection only highlights hub, not items (to be addressed later).
