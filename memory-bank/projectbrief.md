# Project Brief: BBOS7 Launcher

## Core identity
Native Android launcher that recreates the **BlackBerry OS 7** home screen experience, optimized for **square displays** (e.g. Zinwa Q25 720×720).

## Scope
- **In scope:** Home screen, app grid/list, dock, notifications, search, status bar, header, settings, icon packs, widgets, backup/restore of settings. Single Activity launcher with overlays (search, notification hub). No lock screen or system UI replacement beyond launcher.
- **Out of scope:** Replacing system status bar or navigation bar (optional hide only); full BB7 OS clone; non-Android platforms.

## Core requirements
1. Replace default HOME intent: single main Activity as launcher.
2. App discovery and launch: grid or list, multiple pages (Frequent, Favorites, All, custom), sorting, hidden apps.
3. Dock: pinned apps and intent shortcuts; swipe-up actions; reorder.
4. Notifications: ticker, hub overlay, optional per-app “applets” with counts.
5. Search: fuzzy in-app search; type-to-search from home; optional external search (app/shortcut/inject).
6. Theming and UX: accent color, opacity controls, icon packs, click highlights, header/action bar tap actions.
7. Persistence: all settings in SharedPreferences; export/import JSON; Room DB for app stats (launch count, favorites).

## Goals
- Nostalgic but functional: BB7 look and feel without sacrificing Android app compatibility.
- Configurable: most UI and behavior tunable in Settings.
- Reliable on target devices (Zinwa Q25, Duoqin F22 Pro, square/small screens).
- Minimal restarts: features should refresh when settings or data change where feasible.
