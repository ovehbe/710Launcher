# BBOS7 Launcher

A native Android launcher that recreates the BlackBerry OS 7 home screen experience,
optimized for the Zinwa Q25's 720x720 square display.

## Features

- **Custom Status Bar**: Battery, carrier, signal strength, network type
- **BB7 Header**: Date and large clock with BB Alphas font
- **Tabbed App Grid**: Frequent / All / Favorites with swipe navigation
- **Universal Search**: Real-time app filtering, phone number dial detection
- **Sound Profiles**: All Alerts Off, Normal, Loud, Medium, Silent, Vibrate
- **Notification Hub**: Captures and displays notifications in BB style
- **Icon Pack Support**: Nova/Apex compatible icon packs
- **Dock Bar**: 5-6 pinned apps at the bottom

## Building

Open in Android Studio or build from command line:

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Target Device

- Zinwa Q25: 720x720 IPS LCD, 208 DPI, Android 14
- Min SDK: 26 (Android 8.0)

## Permissions

- `READ_PHONE_STATE` - Carrier/signal info for status bar
- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` - Network type display
- `MODIFY_AUDIO_SETTINGS` - Sound profile switching
- `QUERY_ALL_PACKAGES` - App grid listing
- `BIND_NOTIFICATION_LISTENER_SERVICE` - Notification hub
