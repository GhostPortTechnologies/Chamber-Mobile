# Changelog

## License change (24 November 2026)

- Chamber mobile moves from AGPL-3.0-only to the Functional Source License, Version 1.1, ALv2 Future License
  (FSL-1.1-ALv2), alongside Chamber. Free for your own team; no competing commercial product or service; each
  version becomes Apache-2.0 two years after it is published.
- Everything published before this change was released under AGPL-3.0-only, and those copies stay under it.
- Dependency check before the change (declared dependencies and the npm lockfile): none is GPL or AGPL. Shipped:
  Capacitor (MIT), tslib (0BSD), Cordova Android, AndroidX, Kotlin, OkHttp, Firebase Messaging (Apache-2.0).
  The full Gradle dependency tree was not resolved.

## 0.1.0 (unreleased): PR #1, Capacitor shell

- Capacitor 8 app for Android and iOS that loads the hosted Chamber app
  (`https://chamber.ghostporttechnologies.com/app`), so sign-in and every Chamber release work as in the browser.
- Navigation limited to Chamber's domain (`allowNavigation`, `WKAppBoundDomains`). Other links open in the
  system browser.
- "Chamber is offline" page (`www/offline.html`) via `server.errorPath`.
- Chamber icon on a dark `#0A0D0B` adaptive-icon background, plus splash screens.
- GitHub Actions: Android debug APK artifact on every PR, unsigned iOS simulator build; Gradle errors show as
  annotations.
- First green build 2026-11-21: run 44963784892 (Android ✅, iOS ✅).
- Android: the app keeps Chamber clear of the status bar, camera cutout, gesture bar and keyboard (native padding,
  dark bars with light icons). The header's buttons were under the clock and battery icons. iOS and the web app get the
  proper fix in Chamber itself (T-0338). **Verified** by the owner on their Android phone, 2026-11-22 (build a34020b).
- Docs: README (why native, against ROADMAP.md) and `docs/SOP.md`.
