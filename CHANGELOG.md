# Changelog

## Unreleased: native Android app (T-0358), push (T-0349)

- Native push, ported from the PR #2 Java code to Kotlin: same rules (content-free wake-up, Allow/Deny only for short
  readable commands and only after unlocking, nothing on the lock screen). A tap opens the Questions tab.
  Signing out drops the push token and clears Chamber's notifications. Push stays off until the Firebase config lists
  the `.dev` test app. Built and unit tested in CI, not yet tried on a phone.
- One shared sign-in vault and API client for screens, push and notification buttons.
- The Questions screen and the confirm prompt mark hidden characters in an agent's request, like notifications do.
- CI: the signing-key and fingerprint steps are shared actions (`.github/actions/`), not copies in each job.
- Driving mode (T-0321), on by default. While Android's activity detection says you're in a vehicle, or you tapped
  "I'm driving now", the app shows one hold screen with nothing to read or tap, holds its notifications, removes
  Allow/Deny buttons, and refuses answers from notifications. When the drive ends, one quiet notification says what
  waited. "I'm not driving" (fingerprint or PIN) pauses it for this trip: until the vehicle has stopped 10 minutes
  and a new trip starts, or 12 hours. Turning it off completely is in Settings only, behind a plain warning, a tick
  and fingerprint or PIN, and is refused while the phone says you're in a vehicle. Settings is new (sign out moved
  there). Not yet: telling agents you're driving (needs the server), a spoken alert, Android Auto.
- Weak signal: a message that fails to send stays in the box to try again (it used to be cleared at once and lost).
  A line says when Chamber can't be reached, and after reconnecting the app reloads what changed meanwhile, which
  the live stream never announced. A 502 while Chamber restarts now backs off instead of reconnecting every second.
- Alerts without Google (T-0388): on a phone without Google Play (GrapheneOS, for example), alerts come through a
  UnifiedPush app such as ntfy from F-Droid. The wake-up is encrypted to this phone's keys and says only "wake"; the
  app then asks Chamber what is waiting, with the same rules as Google's push (driving mode, Allow/Deny only after
  unlocking, sign-out stops it). A message that didn't decrypt, or says anything else, wakes nothing. Settings >
  Notifications shows the route, offers the choice on a phone with both, says which push servers the workspace
  allows when Chamber refuses one, and has "Send a test notification". UnifiedPush connector 3.3.6. Built and unit
  tested in CI, not yet tried on a phone.

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
