# Chamber mobile: standard operating procedures

How work on this app gets done, built, tested and released. Keep it short and keep it true: change this file in the
same pull request as the change it describes.

## 1. Making a change

1. The work has a ticket in Chamber (e.g. T-0321), or the owner asked for it in #general.
2. Branch from `main` with a name that says what it is: `setup/…`, `feat/push`, `fix/icon-background`.
3. Commit in small steps. The message says *what* changed and *why*.
4. Push the branch and open a pull request.
5. Nothing is merged into `main` without the owner's review. Nobody pushes to `main` directly.

## 2. Builds (GitHub Actions)

Every pull request and every push to `main` runs `.github/workflows/build.yml`:

| Job | Runs on | Produces |
|---|---|---|
| Android (debug APK) | ubuntu-latest | unit tests, then an APK in a private draft release `test-<branch>` |
| Native Android (Kotlin) | ubuntu-latest | unit tests, then an APK in a private draft release `native-<branch>` |
| iOS (simulator build, unsigned) | macos-latest | nothing, just proves the Xcode project compiles |

- Builds run on GitHub Actions, so contributors don't need the Android SDK or a Mac.
- Steps both Android jobs need (test signing key, signing fingerprint, the draft release) live once in
  `.github/actions/`. Change them there, not by copying a step into a job. The draft-release step posts gh's error as
  annotations, so a failed upload can be read without signing in. Each APK's file name ends with its commit
  (`chamber-native-feat-native-android-12a14d5.apk`): install the newest one, which the release notes name.
- A failure that says "Could not resolve" or "Could not GET" is a download problem, not the change: re-run it before
  debugging.
- **When a build fails:** the "Report the Gradle error" step turns the key lines of the Gradle log into
  annotations, so a failure can be diagnosed from the PR page or the public API without the full log. Fix it on the same branch, push, and the PR re-runs.

## 3. Testing a build on a phone

1. Signed in to GitHub, open **Releases** and find the draft "Test build: <branch>". Drafts are visible only to
   people who can push to this repo. Nothing becomes a public download until the owner says "ship it".
2. Tap the `.apk`. Allow installs from Files or the browser for this install only, then turn it
   off again (Android: Settings → Apps → Special app access → Install unknown apps).
3. Check, every time:
   - [ ] Nothing is under the status bar or gesture bar: the header's ☰, search and ⚙ sit fully below the clock and
         battery, and the message box stays above the keyboard
   - [ ] The icon is dark (#0A0D0B) with the Chamber mark, not white
   - [ ] You can sign in, and you're still signed in after the app is closed and reopened
   - [ ] Links to other sites open in the system browser, not inside the app
   - [ ] In airplane mode, the app shows "Chamber is offline", and "Try again" works once you're back online
   - [ ] Push: Settings > Notifications test appears with the app closed
   - [ ] A short command question shows Allow once and Deny, and the phone asks to be unlocked before either works
   - [ ] A long command shows no buttons, and a tap opens the question
   - [ ] On the lock screen, neither the agent's name nor the command shows
   - [ ] Driving mode (native app): Settings > "I'm driving now" shows the hold screen at once, with nothing to read
         or tap; a push meanwhile shows nothing; "I'm not driving" asks for a fingerprint and then the app is back with
         the "paused for this trip" line; "Turn off driving mode" is refused while the phone says you're in a vehicle
   - [ ] Weak signal (native app): in airplane mode, type a message and tap Send; the text stays in the box and the
         "No connection" line shows; back online, the line goes, new messages from meanwhile appear, and Send works
   - [ ] Alerts without Google (native app, T-0388): on GrapheneOS with ntfy from F-Droid and no Google Play, Settings >
         Notifications says "UnifiedPush (ntfy)"; "Send a test notification" shows the test; an agent's question
         wakes the phone (held while driving); after signing out, nothing more arrives. On a phone with both, switching
         to UnifiedPush and back works and nothing arrives twice. If alerts stop after a burst of them, note how many
         and when: it may be ntfy.sh's rate limit
   - [ ] Sign-in (native app): a wrong password says so and keeps the form; the code step works; with no signal at
     open, the app says it couldn't open, and "Try again" works once the signal is back. After a crash, the next open
     shows "Chamber closed unexpectedly" with "Copy details".
   - [ ] Tickets queue (native app, T-0318): "Needs your decision" is on top with the oldest high-priority item
         first, each with "waiting N days"; your to-dos are a separate list; "Show done and rejected" loads them
   - [ ] Anything the PR itself adds (push, biometrics…)
4. Report the result in the PR or in #general. Merge only after the checks pass.

Test builds are signed with one stable test key (GitHub secrets `ANDROID_TEST_KEYSTORE_B64` and
`ANDROID_TEST_KEYSTORE_PASSWORD`), so each new test APK installs as an update. If those secrets are missing, every
build gets a one-off key and Android refuses the update ("app not installed"): uninstall the old one first.

Debug APKs are for testing only. They're signed with a throwaway debug key, so a store build later installs as a
different app. Uninstall the test build first.

## 4. Secrets

- Never in the repo: keystores, `.p12`, provisioning profiles, `google-services.json`,
  `GoogleService-Info.plist`, `.env` (enforced by `.gitignore`).
- Store signing keys and Firebase/Apple config go into GitHub Actions secrets once the accounts exist (T-0323).
- Keys and tokens are never pasted into issues, PRs or screenshots.

## 5. Rules carried over from Chamber

- Push notifications stay **content-free** ("something new for you"), like Chamber's `push.js`. No message text
  goes to Apple's or Google's push servers.
- **Driving mode is on by default** (T-0321). Turning it off is the user's explicit choice and risk, and is logged.
- The app only navigates inside `chamber.ghostporttechnologies.com`. Widening that list needs its own PR and a reason.

## 6. Lessons learned

| Date | What happened | What we do now |
|---|---|---|
| 2026-11-21 | First Android build failed and the full log needs a GitHub sign-in | CI posts Gradle errors as public annotations (section 2) |
| 2026-11-21 | `@capacitor/assets` made an adaptive icon pointing to `@mipmap/ic_launcher_background`, which it never generated, and defaulted to white | Icon background is the color resource `#0A0D0B`. After regenerating icons, check `mipmap-anydpi-v26/*.xml` |
| 2026-11-22 | `allowBackup` was left on, so Android could copy the sign-in to cloud backup | Backup and device transfer are off |
| 2026-11-22 | A new test APK wouldn't install over the old one: every CI build had its own random debug key | One stable test key from GitHub secrets, and versionCode = CI run number |
| 2026-11-21 | Tapping the Actions artifact on a phone did nothing (zip, sign-in only) | CI also publishes each branch's APK as a `test-<branch>` pre-release, a direct link |
| 2026-11-25 | Both test-build uploads failed: GitHub answered HTTP 500 when `--clobber` deleted the old APK, and the step's log needed a sign-in | Shared draft-release step posts gh's errors as annotations; each APK has the commit in its name, so no delete has to succeed |
