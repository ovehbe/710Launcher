# Active Context

## Current work focus
- **Stability hardening (in progress):** Eliminating the crash-on-restore class and the memory pressure that gets the launcher killed in the background. Phases 1–3 of the stability pass are implemented; on-device verification pending.
- **Next after stability:** Remaining polish items (ticker/count refresh, Frequent/All persistent hide, applet icon quality) and the backlog features.

## Recent changes (stability pass, v1.5.0 baseline)
Driven by four crash logs from a Xelex Q25 (SDK 34, app 1.5.0/code 13): three `IllegalStateException: Fragment no longer exists for key f#N` from `ViewPager2.setAdapter`, and one `lateinit property pagerAdapter has not been initialized`.

**Root cause:** `LauncherActivity.onCreate` created and attached the pager adapter inside a coroutine that first awaited a full `repository.loadApps()` scan. That deferred `setAdapter` past the activity's state-restore pass (so ViewPager2 replayed saved state against fragments the FragmentManager no longer held) and left a window where `repository.onAppsChanged` could hit the uninitialized `pagerAdapter`.

Phase 1 — crashes:
- `setupPager()` now runs synchronously in `onCreate`; data arrives later via `refreshAll()`.
- `appPager.isSaveEnabled = false`, and `android:support:fragments` / `android:fragments` stripped from `savedInstanceState` before `super.onCreate`. A home screen has no state worth restoring.
- `pagerAdapter` is a nullable field, not `lateinit` — removes ~20 scattered `isInitialized` checks, one of which was missing.
- `AppPagerAdapter` gained stable `getItemId`/`containsItem` keyed on page ID (positions shift when All/Frequent are hidden), resolves fragments through the FragmentManager instead of a position-keyed map that leaked and went stale, and injects dependencies via `FragmentOnAttachListener` so restored fragments aren't left with a null repository.

Phase 2 — memory:
- `AppRepository` gained a byte-sized `LruCache` for rendered icons. Previously `getIconForPage` ran `applyGlobalShape` on every RecyclerView bind, allocating two bitmaps and a throwaway `IconPackManager` each time.
- `applyGlobalShape` and the applet/contact/command icon paths reuse a shared `shapeRenderer` and a memoized `adHocPacks` map instead of constructing and re-parsing icon packs per call.
- `loadApps()` is serialized behind a `Mutex`; `onResume` no longer kicks off two concurrent re-index passes.
- `onResume` is incremental: icon packs are only re-parsed when the pack signature changed (and then off the main thread), otherwise it calls the new cheap `refreshStats()` (DB read only, reuses loaded icons) so the Frequent page stays current.

Phase 3 — lifecycle:
- All ad-hoc `CoroutineScope(...)` replaced with `lifecycleScope`; `AppRepository` owns one cancellable `appScope`.
- `NotifListenerService.onNotificationsChanged` registered in `onStart`, cleared in `onStop` (it's a static holding the activity).
- Notification refresh storms collapsed: the service debounces changes (120 ms), and the activity coalesces into one immediate plus one settle refresh (`scheduleTickerRefresh`) instead of seven staggered ones.
- Manifest: `configChanges="keyboard|keyboardHidden|navigation|screenSize|smallestScreenSize|screenLayout"` on `LauncherActivity` so the Q25's physical keyboard doesn't force a recreate. `locale|uiMode|density|fontScale` deliberately left out so those still recreate.

## Next steps
1. **Verify on device:** sideload the debug APK on the Q25 and confirm no crash-on-restore and no background death. Debug build uses `applicationIdSuffix = ".debug"`, so it installs alongside the release build and must be selected as home.
2. Optional Phase 1B: drop `FragmentStateAdapter` entirely — `AppGridFragment` uses no fragment capability (no back stack, child fragments, ViewModel, or saved state), so a plain `RecyclerView.Adapter` would permanently remove the crash class.
3. Then resume the feature/polish backlog.

## Active decisions and considerations
- **Branching:** Work on `dev`; do not push or open PRs without being asked.
- **Scope:** Minimal, targeted changes; when in doubt, ask before touching unrelated areas.
- **Build toolchain:** This machine had no JDK/Android SDK. A user-local toolchain lives at `~/toolchain` (Temurin JDK 17 + SDK with platform-34/build-tools-34). `local.properties` points at it and is gitignored.
