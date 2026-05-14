# Metro Launcher

An Android home-screen launcher that faithfully recreates the **Modern UI / Metro** aesthetic of Windows 10 Mobile: resizable colored tiles, live-tile notification previews, an alphabetical app drawer, and a fully customizable wallpaper.

![minSdk 24](https://img.shields.io/badge/minSdk-24-0078D7?style=flat-square)
![targetSdk 34](https://img.shields.io/badge/targetSdk-34-0078D7?style=flat-square)
![Language Kotlin](https://img.shields.io/badge/language-Kotlin-0078D7?style=flat-square)
![Tile sizes](https://img.shields.io/badge/tile%20sizes-1×1%20%7C%202×2%20%7C%204×2%20%7C%204×4-0078D7?style=flat-square)
![License](https://img.shields.io/badge/license-Personal%20(free%20to%20adapt)-lightgrey?style=flat-square)

---

## Features

### Start Screen
- **Resizable tiles** in four sizes matching Windows Phone 10: Small (1×1), Medium (2×2), Wide (4×2), Large (4×4).
- **4-column grid** (classic WP) or **6-column compact grid** — switchable from Settings.
- **Drag & drop reordering**: long-press a tile to enter edit mode (tiles wobble), drag it anywhere. A white overlay highlights a valid drop target; red signals a collision. Tap anywhere else to exit.
- **Auto-placement** fills gaps top-to-left, mirroring the original Windows Phone Start screen behavior.

### Live Tiles
- **Notification badge**: Wide and Large tiles show a numeric badge (top-right corner) with the count of active notifications.
- **Text live tile**: the latest notification's title and body appear directly on Wide and Large tiles.
- **Messaging apps** (WhatsApp, Telegram, Signal, Messenger, Messages…): tiles show the **total unread message count** aggregated across all chats, plus the **last message preview** (sender + text). Data is extracted from `MessagingStyle`, so any app using the standard Android notification style is supported automatically — no per-app integration required.
- **Media tiles** (Spotify, YouTube Music, VLC, podcast apps…): tiles for apps playing audio become a **mini-player** — album art as background, track title, artist name, and an **animated progress bar** on the bottom edge. Tap the play/pause icon (top-right) to toggle playback; Wide and Large tiles also show **previous** and **next** skip buttons. The rest of the tile still launches the app. Works with any app that publishes a `MediaSession`.

### Tile Customization
- **10 Metro accent colors**: Blue, Teal, Green, Red, Magenta, Purple, Orange, Brown, Gray, Graphite — selectable per tile via long-press menu.
- **Auto-color**: the initial tile color is extracted automatically from the app icon using `androidx.palette`.
- **Transparent tiles**: tiles can be set as transparent to reveal the wallpaper behind them.
- **Font size for tiles**: Small / Normal / Large — configurable in Settings.

### App Drawer
- Opens via swipe left, swipe up, double-tap on the background, or tap the "›" arrow (bottom-right).
- **Alphabetical sections** with A / B / C… headers and a side index for fast scroll.
- **Search bar** for instant filtering.
- Long-press an app to **pin it to Start**, open App Info, or uninstall.
- **Font size** for the drawer: Small / Normal / Large.

### Wallpaper & Settings
- Uses the system wallpaper (`WallpaperManager`) by default, or pick a custom image via the image picker (persistent permission).
- Settings accessible via long-press on any tile or by swiping down from the top.
- **Portrait-only lock** option.
- Shortcut to system Notification Access settings.
- **Reset Start** (clears all tiles).

---

## Architecture

```
MetroLauncher/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml           # Declares the app as HOME + DEFAULT launcher
│       ├── java/com/metrolauncher/
│       │   ├── MetroApp.kt               # Application singleton — app icon cache
│       │   ├── model/                    # Tile, TileSize, TileKind, AppInfo, WeatherCondition
│       │   ├── util/                     # AppLoader, TileStorage, GridPacker, ColorUtils,
│       │   │                             # Prefs, AlarmProvider, CalendarProvider,
│       │   │                             # GalleryProvider, MediaInfoCache,
│       │   │                             # WeatherProvider, FaviconCache
│       │   ├── view/                     # TileView (custom Canvas), TileGridLayout (ViewGroup),
│       │   │                             # SearchBarView, SearchPullDownLayout, TileMaskView,
│       │   │                             # WeatherBackgroundRenderer
│       │   ├── adapter/                  # AppListAdapter (sectioned drawer)
│       │   ├── service/                  # NotificationListener, PackageChangeReceiver
│       │   └── activity/                 # MainActivity, AllAppsActivity, SettingsActivity,
│       │                                 # DrawerFragment, StartFragment, ShareToStartActivity
│       └── res/
│           ├── layout/                   # XML layouts
│           ├── values/                   # colors, strings, dimens, themes
│           ├── anim/                     # slide transitions
│           ├── xml/settings_prefs.xml    # PreferenceScreen definition
│           ├── drawable/                 # Launcher icon (vector)
│           └── mipmap*/                  # Adaptive icon + round fallback
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

### Key Components

| Component | Description |
|---|---|
| `TileView` | Custom `View` that draws a tile entirely on `Canvas`: colored background, icon, label, notification badge, live-tile text, media art and controls. Everything is hand-drawn for pixel-perfect control and smooth performance. |
| `TileGridLayout` | Custom `ViewGroup` implementing an absolute N-row × 4-column grid. Each tile occupies `size.cols × size.rows` cells at position `(row, col)`. Handles drag-and-drop in-place. |
| `GridPacker` | Auto-placement algorithm that fills the grid top-to-left, replicating Windows Phone Start screen behavior. |
| `NotificationListener` | `NotificationListenerService` that captures title, body, `MessagingStyle` data, and `MediaSession` metadata, forwarding them to `MainActivity` via local broadcast. Requires user-granted `BIND_NOTIFICATION_LISTENER_SERVICE` permission. |
| `ColorUtils` | Uses `androidx.palette` to extract a vibrant color from the app icon when a new tile is pinned; the color can be overridden manually at any time. |

---

## Gestures

| Gesture | Action |
|---|---|
| Tap tile | Launch app |
| Long-press tile | Open menu: resize / color / transparency / remove |
| Swipe ← on Start | Open All Apps drawer |
| Swipe ↑ on Start | Open All Apps drawer |
| Double-tap on Start | Open All Apps drawer |
| Tap "›" (bottom-right) | Open All Apps drawer |
| Swipe → in drawer | Return to Start |
| Long-press app in drawer | Menu: Pin to Start / App Info / Uninstall |

---

## Requirements

- **Android Studio** Hedgehog (2023.1) or newer
- **JDK 17** (bundled with Android Studio)
- Device / emulator running **Android 7.0 Nougat (API 24)** or higher

---

## Building

### Android Studio (recommended)

1. `File → Open…` and select the `MetroLauncher/` folder.
2. Wait for the first Gradle sync (~1–2 minutes).
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
4. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Command line

The repo does not include the `gradle-wrapper.jar` binary. Generate it once with a globally installed Gradle, or let Android Studio create it on the first sync:

```bash
cd MetroLauncher
gradle wrapper --gradle-version 8.2   # only if Gradle is installed globally
./gradlew assembleDebug
```

For a signed release build:

```bash
./gradlew assembleRelease
# Requires keystore configuration — see the Android signing documentation.
```

---

## Installation

**Via ADB:**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Manually:** copy the APK to your device and install it (enable *Install from unknown sources* for your file manager if prompted).

---

## First Launch

1. **Set Metro Launcher as the default home app:**  
   `Settings → Apps → Default apps → Home app → Metro Launcher`  
   *(path may vary slightly by manufacturer)*

2. **Grant Notification Access** (required for live tiles):  
   A dialog appears on first launch — tap *Open Settings* and enable the toggle next to **Metro Launcher**.  
   Manual path: `Settings → Privacy → Notifications → Notification access → Metro Launcher`

3. Six example tiles (the first six installed apps, alphabetically) are placed automatically. Long-press any tile to resize, recolor, or remove it.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android core APIs |
| `androidx.appcompat:appcompat` | 1.6.1 | Backward-compatible Activity/Fragment support |
| `com.google.android.material` | 1.11.0 | Material Design components |
| `androidx.constraintlayout` | 2.1.4 | Flexible XML layouts |
| `androidx.recyclerview` | 1.3.2 | All Apps drawer list |
| `androidx.viewpager2` | 1.0.0 | Horizontal paging between Start and drawer |
| `androidx.palette:palette-ktx` | 1.0.0 | Auto-extract accent color from app icons |
| `androidx.preference:preference-ktx` | 1.2.1 | Settings screen |
| `com.google.code.gson` | 2.10.1 | Tile/config persistence via SharedPreferences |
| `kotlinx-coroutines-android` | 1.7.3 | Async icon loading, notification processing |

---

## Roadmap

- [ ] Full color picker (currently limited to 10 preset Metro colors)
- [ ] Android widget support (`AppWidgetHost`)
- [ ] Light theme
- [ ] Multiple Start pages
- [ ] Start configuration backup / restore
- [ ] Animated live tiles (flip between icon and content)

---

## License

Personal project — feel free to adapt it.  
No affiliation with Microsoft. "Windows Phone" and "Metro" are trademarks of Microsoft Corporation, used here solely to describe the visual aesthetic being replicated.
