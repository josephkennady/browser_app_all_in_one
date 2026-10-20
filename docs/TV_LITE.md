# AA Browser TV Lite

A separate Android TV-only app for Android 11 and newer, under `tv/`. The existing mobile and car apps are unchanged by this module.

## Install

Install `AA-Browser-TV-Lite-1.0.apk` from the `dist/` folder. It appears as **AA Browser TV Lite** in the TV launcher. Its package ID is `com.kododake.aabrowser.tv`, so it installs alongside the earlier browser. Bookmarks and sign-ins are separate; nothing is deleted from the old app.

The compact APK is minified, resource-shrunk, non-debuggable, and signed with this machine's development key for sideload testing. Keep that key for future updates; this is not a Play Store production release.

## Mi remote

- Home, toolbar and dialogs: arrows move focus; OK selects. The highlighted outline identifies the selected control.
- Web pages: arrows move a visible pointer; OK clicks. Hold arrows to move faster. At the top/bottom edge, continuing Up/Down scrolls the page.
- Back leaves fullscreen first, then returns from pointer mode to the toolbar. Back again navigates back, or returns Home. At Home, Back asks before exiting.
- **Use pointer** returns control to the web page. No Menu button is needed.
- The TV's on-screen keyboard receives normal remote keys while visible.
- Bookmarks uses native modal dialogs; remote focus stays inside the open dialog.

## Why the APK is small

The TV app uses Android platform widgets and the TV's installed Android System WebView. It includes no Compose/Material/AndroidX runtime, car integration, analytics, QR libraries, Kotlin runtime, image downloads, custom backgrounds, tab engine or ad-blocking scripts. Only one web document exists at a time. Going Home discards that document, releasing its video/page resources. Offscreen prerasterization is disabled. Hardware acceleration remains enabled for media.

This saves app code and background-tab memory. Websites and video decoding still consume memory and use the TV's WebView/network/codec implementation; a small APK alone cannot guarantee smooth playback.

## YouTube and loading errors

The YouTube shortcut opens `https://m.youtube.com/` using the installed WebView's default identity. No code intercepts YouTube responses or changes playback rate, mute state, or seek position. Ad blocking is not included.

Main-frame loading failures, including `net::ERR_FAILED`, show the description, code, Retry, another-site search, and Home. Failed subresources do not replace an otherwise usable page with an error screen. Renderer crashes are handled by destroying/recreating the WebView and offering Retry; there is no automatic reload loop or SSL bypass.

If playback fails only on the TV, compare another website, try a lower video quality, and check Android System WebView updates in the TV Play Store. Home → TV help shows the installed WebView version. Error handling does not claim to fix an external connection, unsupported codec, or YouTube service issue.

## Build and tests

With JDK 21 and the existing Android SDK:

```sh
bash gradlew :tv:packageTvLite :tv:testDebugUnitTest :tv:lintCompact
```

APK output: `tv/build/outputs/apk/compact/tv-compact.apk`.

Three unit tests cover pointer bounds/edge scrolling/unmeasured viewports and safe URL/search resolution. Android lint checks API 30 compatibility. Emulator testing uses the optimized compact APK with Android TV API 34; the user's Android 11 Mi TV still requires physical testing.

## Verified in this session

- Compact build, all three unit tests, and `lintCompact` passed (lint has warnings, no errors).
- TV emulator API 34: remote-only Home selection, pointer movement, OK activation of YouTube search, TV keyboard, YouTube search results and embedded video playback worked.
- Back returned to the toolbar; arrows/OK opened the native Bookmarks dialog with its list focused.
- An intentionally invalid domain produced a readable main-frame error with Retry focused and Search/Home recovery controls.
- APK inspection found only app bytecode, manifest and small resources; no native libraries, mobile/car packages, or Kotlin runtime resources.
- No physical Mi TV test, long-duration playback test, renderer-crash injection, or fullscreen video test was performed. The user's reported YouTube `ERR_FAILED` was not reproduced in the emulator.
