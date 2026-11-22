# Changelog

## Unreleased: PR #2, Android push (compiled and unit tested only, not tried on a real phone)

- Native Firebase push that carries no content. The phone asks Chamber what is waiting, as `sw.js` does.
- Allow once and Deny on a notification, only for commands that are fully readable there (300 characters, 4 lines,
  no hidden characters), only after unlocking (Android 12+), and only if the question is still the same one.
- Nothing about the question shows on a locked screen. A tap opens the question.
- Answers Chamber's "Send a test notification" check.
- The sign-in is kept out of cloud backup and phone-to-phone transfer.
- Waiting on: the Firebase config secret, and Chamber's server accepting and sending to native tokens (T-0335).
- iOS push waits for the Apple Developer account (T-0323).
- Test builds are private draft releases.

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
