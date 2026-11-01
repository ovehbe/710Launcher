# 710 Launcher — gap audit (v1.5.5)

What this launcher is missing compared with solid modern launchers (Lawnchair, Niagara, Nova,
Kvaesitso, stock Pixel). Built from reading the code at v1.5.5, not from the feature list.
Ordered by user impact. Effort is technical scope: **S** = one file or component, **M** = a few
components or a new subsystem, **L** = cross-cutting or touches the data model everywhere.

Items marked *(planned)* are queued for the 1.6.0 series, one PR each.

| # | Gap | Why it matters | Effort |
|---|-----|----------------|--------|
| 1 | **Full backup and restore** *(planned)* | Only the settings prefs can be exported (`LauncherPrefs.exportToJson`). The dock (`dock` prefs), favourites and launch stats (Room `app_stats`), and pinned-shortcut icons (in `cacheDir`) are lost when the user changes phones. | M — codec, Room access, SAF and atomic apply |
| 2 | **Swipe down for notifications** *(planned)* | It's the most common home gesture on Android. `dispatchTouchEvent` only handles the bottom swipe and double-tap, so the shade needs a reach to the top edge. | S — gesture in `LauncherActivity` and a reflection helper |
| 3 | **Usage-aware search ranking** *(planned)* | `AppRepository.searchApps` sorts by raw all-time `launchCount`, with no recency. An app used heavily months ago outranks the one used every day, and an exact or prefix match doesn't beat a substring match. | M — ranker, Room migration, cached scores |
| 4 | **Web suggestions in search** *(planned)* | When nothing matches, the overlay shows an empty list and the user has to know Enter opens Google. Suggestions are expected in every modern launcher search. | M — debounced, cancellable network fetch and a result type |
| 5 | **Dialer row on non-numeric queries** *(planned)* | `convertToDialDigits` T9-maps letters, so typing `sjsj` offers to dial `44`, and Enter never dials even a real number. | S — `SearchOverlay` |
| 6 | **Work profile apps** | `queryIntentActivities` and `ShortcutHelper` only use `Process.myUserHandle()`, so work-profile apps are invisible: a dealbreaker for managed devices. | L — `LauncherApps.getActivityList` per `UserHandle`; component keys must include the user everywhere (prefs, Room, dock) |
| 7 | **Folders** | Nothing in the code groups apps. Custom pages partly substitute, but users expect to drop one icon on another on the home screen and in the dock. | L — new model, persistence, UI, drag-and-drop targets |
| 8 | **Pin requests from apps** (`ACTION_CONFIRM_PIN_SHORTCUT` / `_APPWIDGET`) | There is no `PinItemRequest` handling, so "Add to home screen" in Chrome, Maps, Contacts and others fails silently. Only the legacy `CREATE_SHORTCUT` picker works. | M — confirm activity, then add to a page or dock |
| 9 | **Uninstall from long-press** | The app menu has App info, Hide, Rename and Change icon, but no Uninstall. Removing an app takes three extra taps through Settings. | S — `ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` entry |
| 10 | **Settings organisation** *(planned)* | `SettingsActivity` is one 2,300-line scroll of buttons in "Label: value" format with few headers. Finding a setting means scrolling, and the toolbar is inside the rebuilt container. | M — grouped sections, row component, theme |
| 11 | **Notification dots on icons** | Page grids (grid view) show a count badge per package. The dock and list view still show nothing. | S — pass `badgeResolver` to `DockBar` binding and add a badge to `item_app_list` |
| 12 | **`LauncherApps.Callback` for package changes** | A `PACKAGE_*` broadcast receiver misses suspended, unsuspended and quiet-mode apps and profile changes, so the list goes stale until "Refresh launcher". | S/M — swap the receiver, pairs with #6 |
| 13 | **Landscape and large screens** | Every activity is `screenOrientation="portrait"`, so tablets, foldables and docked phones are stuck. It also clashes with the Android 16 ignore-orientation rule for large screens. | M — layouts, grid metrics per width |
| 14 | **Accessibility** | Only two `contentDescription`s exist in the codebase. Icons, dock and tab bar items have no TalkBack labels, and there are no custom actions for the long-press menus. | M — labels, `AccessibilityDelegate`s |
| 15 | **Localization and RTL** | `strings.xml` has 42 strings, while Settings and menus use hundreds of hardcoded literals. `supportsRtl="false"`. | L — string extraction across `SettingsActivity` / `LauncherActivity` |
| 16 | **Themed (monochrome) icons** | No use of `AdaptiveIconDrawable.getMonochrome()`, so there's no Android 13+ themed-icon option. Users have to install an icon pack for a uniform look. | S/M — an icon path in `AppRepository` plus a setting |
| 17 | **Light theme / Material You** | Dark-only (`Theme.BBOS7`, no `values-night` or dynamic colour). That's intentional for the BB OS 7 look, but there's no option at all. | M — colour resources, theme switch |
| 18 | **Search beyond apps and contacts** | No calculator, unit conversion, settings-page search or shortcut search. Custom "search commands" cover some of this with manual setup. | M per provider |
| 19 | **Hidden-app privacy** | Hidden apps are only filtered from lists. There's no lock (biometric or PIN) to reveal or launch them. | S/M — `BiometricPrompt` gate |
| 20 | **Test coverage** | The only unit test was `SearchNormalizerTest`. Gestures, prefs migration, dock ordering and widget persistence have none, and there are no instrumented tests. | Ongoing |
| 21 | **Known reliability items** (from `memory-bank/progress.md`) | The ticker and count can go stale after clearing notifications; Frequent or All hidden state is inconsistent; some view changes need a restart; hub items aren't selectable with the trackpad. | S–M each |

## Not gaps (already solid)

These were checked and are fine:

- Per-page and dock icon packs.
- Custom labels and icons per page.
- Widgets with resize.
- Intent shortcuts.
- Hardware-key shortcuts through the accessibility service.
- Contact search with T9.
- Instant search: a precomputed index and a diffed adapter.
- An activity recreate that survives config changes through the process-wide `AppRepository`.
