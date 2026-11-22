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
| Android (debug APK) | ubuntu-latest | `chamber-android-debug-<sha>` artifact, kept 14 days |
| iOS (simulator build, unsigned) | macos-latest | nothing, just proves the Xcode project compiles |

- Builds run on GitHub Actions, so contributors don't need the Android SDK or a Mac.
- **When a build fails:** the "Report the Gradle error" step turns the key lines of the Gradle log into
  annotations, so a failure can be diagnosed from the PR page or the public API without the full log. Fix it on the same branch, push, and the PR re-runs.

## 3. Testing a build on a phone

1. **On a phone:** open the direct link from the build, `https://github.com/GhostPortTechnologies/Chamber-Mobile/releases/download/test-<branch>/chamber-test-<branch>.apk`
   (also on the **Releases** page as "Test build: <branch>"). No zip and no sign-in needed.
   On a computer you can use the run's **Artifacts** zip instead (needs a GitHub sign-in, and doesn't work well on phones).
2. Tap the `.apk`. Allow installs from Files or the browser for this install only, then turn it
   off again (Android: Settings → Apps → Special app access → Install unknown apps).
3. Check, every time:
   - [ ] Nothing is under the status bar or gesture bar: the header's ☰, search and ⚙ sit fully below the clock and
         battery, and the message box stays above the keyboard
   - [ ] The icon is dark (#0A0D0B) with the Chamber mark, not white
   - [ ] You can sign in, and you're still signed in after the app is closed and reopened
   - [ ] Links to other sites open in the system browser, not inside the app
   - [ ] In airplane mode, the app shows "Chamber is offline", and "Try again" works once you're back online
   - [ ] Anything the PR itself adds (push, biometrics, driving mode…)
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
| 2026-11-22 | A new test APK wouldn't install over the old one: every CI build had its own random debug key | One stable test key from GitHub secrets, and versionCode = CI run number |
| 2026-11-21 | Tapping the Actions artifact on a phone did nothing (zip, sign-in only) | CI also publishes each branch's APK as a `test-<branch>` pre-release, a direct link |
