# Chamber mobile

Chamber for Android and iOS. [Chamber](https://github.com/GhostPortTechnologies/Chamber) is GhostPort's
workspace for people and their AI agents: an agent proposes, a person approves, and every step is on the record.

This app is a native shell around the hosted Chamber web app, built with [Capacitor](https://capacitorjs.com) 8.
It loads `https://chamber.ghostporttechnologies.com/app` directly, so every Chamber release reaches the app
without a store update, and sign-in works exactly as in the browser (Chamber's session cookie is
`SameSite=Strict`, which a bundled copy of the pages would break).

## Why a native app (roadmap note)

Chamber's `ROADMAP.md` (2026-11-06) lists native apps as "not on the list on purpose: the installable web app
covers it; revisit only if push reliability on iOS becomes a complaint." On 2026-11-21 the owner decided to
build it now. Notifications weren't reaching the owner, and these planned features need more than a web app can do:

| Feature | Ticket | Status |
|---|---|---|
| Native shell, icons, offline page, CI builds | — | this PR |
| Native push (FCM / APNs), still content-free like `push.js` | — | next |
| Fingerprint / Face ID before approving | T-0318 | planned |
| Driving mode: approvals wait until you've stopped | T-0321 | planned |

## How it's put together

- `capacitor.config.json`: app id `com.ghostporttechnologies.chamber`, the Chamber URL, and the only domain
  the app may navigate inside. Links to any other site open in the system browser.
- `www/offline.html`: shown when Chamber can't be reached (`server.errorPath`).
- `android/`, `ios/`: generated native projects (iOS uses Swift Package Manager, no CocoaPods).
  iOS is limited to Chamber's domain with `WKAppBoundDomains`.
- `assets/`: source icon (Chamber's `icon-512.png`); regenerate with
  `npx @capacitor/assets generate --iconBackgroundColor '#0a0d0b'`.

## Building

Every push and pull request runs `.github/workflows/build.yml`:

- **Android:** a debug APK. Download it from the run's *Artifacts*, unzip it, and install it on a phone
  (allow "install unknown apps" for your browser or file manager).
- **iOS:** an unsigned build for the simulator, to prove the project compiles. Building for real iPhones needs the
  Apple Developer account (T-0323).

Locally you need Node 22+, then:

```sh
npm ci
npx cap sync
# Android: JDK 21 + Android SDK, then: cd android && ./gradlew assembleDebug
# iOS: a Mac with Xcode, then: npx cap open ios
```

## Rules

- Work goes through pull requests. `main` is what has been reviewed and approved.
- No signing keys, keystores or Firebase/Apple config files are ever committed (see `.gitignore`); they live in
  GitHub Actions secrets once the store accounts exist.
- Push notifications must stay content-free, like Chamber's web push: "something new for you", never message text.

## License

AGPL-3.0-only, same as Chamber. Copyright (C) 2026 GhostPort Technologies.
