# Product Context

## Why this project exists
Users want a **BlackBerry OS 7–style home screen** on Android (especially on square or compact devices like Zinwa Q25). Default and most third-party launchers don’t offer this look, so this project fills that gap.

## Problems it solves
- **Visual:** Status bar, header (date/clock), ticker, blocky notification hub, and grid/list layout that recall BB7.
- **Interaction:** Type-to-search from home, configurable tap actions (header, action bar center), dock with swipe-up actions, sound profiles.
- **Customization:** Icon packs, accent color, opacity, hidden apps, hidden pages (All/Frequent), default home tab, notification applets vs simple count.

## How it should work
- **Launch:** Set as default launcher; single main Activity handles HOME.
- **Navigation:** Tabs for app pages (swipe or tap); dock always visible; header and action bar at top; notification ticker/applets in action bar area.
- **Search:** Tap search icon or type from home → overlay with fuzzy results; optional redirect to external app/shortcut.
- **Notifications:** Ticker shows latest (typing animation); tap area opens hub; applets show per-app counts when enabled.
- **Settings:** Single scrollable Settings activity; changes apply on return (recreate where needed); export/import for backup.

## User experience goals
- Feel like BB7 without breaking normal Android usage.
- No unnecessary restarts: ticker/count and UI state should update when notifications or settings change.
- Trackpad/keyboard friendly: no stuck focus highlights; type-to-search with Alt/Shift for symbols.
- Clean, consistent tap feedback (configurable ripple, no default focus ring on key areas).
