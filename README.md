[![Downloads](https://img.shields.io/github/downloads/ovehbe/710Launcher/total)](https://github.com/ovehbe/710Launcher/releases)

<a href="https://github.com/ovehbe/710Launcher/releases">
  <img src="https://img.shields.io/badge/Download-Pre--Release-red?style=for-the-badge" />
</a>

# BBOS7 Launcher

A native Android launcher that recreates the BlackBerry OS 7 home screen experience,
optimized for square displays (e.g. Zinwa Q25 720×720).

## Features

- **Custom status bar**: Battery (with level color), clock, carrier, network type, WiFi, optional Bluetooth / alarm / DND
- **BB7 header**: Date and clock with BB Alphas font; optional hide header; show/hide date and clock; layout (swap, center); tap actions (open app or shortcut)
- **Tabbed app grid**: Frequent, Favorites, All + custom pages with swipe navigation; optional hide All or Frequent pages; default home tab by page ID (persists when pages are hidden); **free-form grid placement** on Favorites and Custom pages — hold any app or shortcut and drop it at any cell, leaving gaps wherever you like; positions are saved per-item and survive relaunches; boundary-locked to the visible screen area with no scrolling
- **Hidden apps**: Long-press any app → Hide (everywhere or this page); manage in Settings → Hidden apps
- **App sorting**: Sort by alphabetical, last opened, last installed, or most used; apply to all pages or selected pages (Settings → Sorting)
- **Universal search**: Fuzzy matching; type-to-search from home (hold Alt/Shift for symbols/capitals); search field hidden when overlay closed; contact search in results (source filter, relevance); dialer layout (QWERTY/T9); contact icon from Contacts app or custom icon pack
- **Context menus**: Long-press any app (grid, search, All) for add to Favorites/pages, pin to dock, hide app, change name/icon, App Info; same for shortcuts
- **Sound profiles**: In-app overlay for Loud, Normal, Low, Silent, Vibrate, All Alerts Off (DND-aware); configurable overlay opacity and selected-row highlight (color and opacity)
- **Overlays**: Only one overlay open at a time (search, sound profile, or notification hub); focus and highlight clearing for consistent touch and trackpad behavior
- **Notification hub**: Ticker (typing animation, center-out), hub with blocky layout; filter system/sticky/silent; centered Clear All button
- **Notification applets**: Optional per-app icons with counts next to ticker (or fallback “N Notifications”); auto-hide applets with 0 count (optional); per-applet custom icons; icon pack for applets
- **Action bar**: Center tap and long press independently configurable — notification hub (default), launch app, or shortcut
- **Click highlights**: Subtle ripple on tap for grid, list, dock, tabs, header, action bar; color (accent or custom) and opacity in Settings; focus highlight disabled for trackpad-friendly use
- **Icon pack support**: Per-page, dock, and applets; search in icon picker; global and fallback icon shaping (default, circle, rounded square, etc.)
- **Dock**: Pinned apps + intent shortcuts; custom dock color; per-icon swipe-up action with bounce animation; long-press opens menu (no accidental launch); horizontal swipe does not trigger tap; **drag-to-reorder** — apps and shortcuts share a single order and any item can be held and moved to any dock slot
- **Tab bar**: Highlight color (accent or custom) and opacity for selected tab
- **Double-tap**: Lock screen or open notification hub (configurable)
- **Color picker**: Custom color options use an HSV graphical picker with hex input and live preview
- **Settings export/import**: Backup and restore all settings (fixed + dynamic keys)
- **Restart**: Restart Launcher from Settings or intent `com.meowgi.launcher710.RESTART_LAUNCHER`

## Building

**Debug (unsigned):**

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

**Release (signed):**

1. Create a keystore (once; **do not commit** it):

   ```bash
   keytool -genkey -v -keystore app/release.keystore -alias bbos7 -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Add `keystore.properties` in the project root (gitignored) with:

   ```properties
   storePassword=your_store_password
   keyPassword=your_key_password
   keyAlias=bbos7
   ```

   See `keystore.properties.example`.

3. Build:

   ```bash
   ./gradlew assembleRelease
   ```

APK: `app/build/outputs/apk/release/app-release.apk`

## Target

- Min SDK: 26 (Android 8.0)
- Target SDK: 34
- Tested on: Zinwa Q25, Duoqin F22 Pro, Samsung Galaxy A51

## Permissions

- `READ_PHONE_STATE` – status bar carrier/signal
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` – network type
- `MODIFY_AUDIO_SETTINGS` – sound profiles
- `QUERY_ALL_PACKAGES` – app listing
- `BIND_NOTIFICATION_LISTENER_SERVICE` – notification hub
- `READ_CONTACTS` – contact search (optional)
