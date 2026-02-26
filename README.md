# BBOS7 Launcher

A native Android launcher that recreates the BlackBerry OS 7 home screen experience,
optimized for square displays (e.g. Zinwa Q25 720×720).

## Features

- **Custom status bar**: Battery (with level color), clock, carrier, network type, WiFi, optional Bluetooth / alarm / DND
- **BB7 header**: Date and clock with BB Alphas font
- **Tabbed app grid**: Frequent, Favorites, All + custom pages with swipe navigation
- **Universal search**: Real-time filtering, optional search icon pack
- **Sound profiles**: Loud, Normal, Medium, Silent, Vibrate, All Alerts Off (sound dialog with distinct icons)
- **Notification hub**: Ticker (typing animation, center-out), hub with blocky layout; filter system/sticky/silent
- **Icon pack support**: Per-page and dock icon packs (Nova/Apex compatible)
- **Dock**: Pinned apps + intent shortcuts; per-icon swipe-up action with bounce animation
- **Double-tap**: Lock screen or open notification hub (configurable)
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
