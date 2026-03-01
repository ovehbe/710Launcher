# Tech Context

## Technologies
- **Language:** Kotlin
- **Build:** Gradle (Kotlin DSL), single module `:app`
- **Min SDK:** 26 (Android 8.0)
- **Target/Compile SDK:** 34
- **JVM:** 17
- **View:** ViewBinding enabled; no Jetpack Compose

## Key dependencies
- AndroidX: Core KTX, AppCompat, Material, RecyclerView, ViewPager2, Lifecycle (runtime, viewmodel)
- Room: runtime, ktx, compiler (KSP)
- Kotlinx Coroutines Android
- No dependency injection framework; manual wiring in Activity/Fragment

## Development setup
- **Project root:** `bbos7launcher` (parent folder may be `bbos7_totalLauncher`).
- **Build debug:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **Build release:** Requires `app/release.keystore` and `keystore.properties` (see README); `./gradlew assembleRelease`
- **Repo:** GitHub `ovehbe/710Launcher`; work on `dev` branch; tag e.g. v1.4.2; merge to `main` when stable.

## Technical constraints
- **Launcher:** Must handle MAIN/HOME/LAUNCHER; singleTask; portrait; adjustPan for IME.
- **Notifications:** NotificationListenerService; user must grant listener permission in system settings.
- **Icon packs:** Nova/Apex–compatible; loaded per-page, dock, and applets; fallback and global icon shaping.
- **Permissions:** READ_PHONE_STATE, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, MODIFY_AUDIO_SETTINGS, QUERY_ALL_PACKAGES, BIND_NOTIFICATION_LISTENER_SERVICE, BIND_APPWIDGET (for widgets).

## Important paths
- **Main Activity:** `app/src/main/java/com/meowgi/launcher710/LauncherActivity.kt`
- **Preferences:** `util/LauncherPrefs.kt` (single SharedPreferences file; export/import JSON)
- **App data:** `util/AppRepository.kt`; Room DB `model/AppDatabase.kt` (AppStats)
- **Settings UI:** `ui/settings/SettingsActivity.kt`
- **Main layout:** `res/layout/activity_launcher.xml`
