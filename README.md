# Personal FlixTor TV client

Native Kotlin / Compose for TV app. Sky-inspired blue glass surfaces, seven household profiles, catalogue/search, seasons, phone QR pairing, email/password sign-in, profile-specific My List and Continue Watching, and Media3 HLS playback with custom remote controls. No WebView.

Targets ONN **Android/Google TV** devices and Chromecast **with Google TV** on Android 10+. ONN Roku TVs and classic cast-only Chromecast devices cannot run this APK. Sky IP061 (Sky Stream) has no supported APK sideloading path.

## Build

Install JDK 17 and Android SDK platform/build-tools 34. Create untracked `local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 tools/build_release.py
```

Set `JAVA_HOME` to JDK 17 if your default Java differs. The release helper creates a private signing key and config in `~/.local/share/flixtor-tv/`. Keep a secure backup: updates need the same key. Signing keys and passwords never belong in Git. Advanced builds can supply `TV_KEYSTORE`, `TV_STORE_PASSWORD`, `TV_KEY_ALIAS`, and `TV_KEY_PASSWORD` directly to Gradle.

## Install and update

Download the signed APK from [GitHub Releases](https://github.com/DylanL0ng/flixtor-tv/releases). Existing installations need one manual signed upgrade to introduce the updater. Subsequent stable releases are checked on launch, every six hours while the app runs, manually from Settings, and after structural failures. Downloads verify checksum, package ID, increasing version code, compatible Android version, and the installed signing certificate. Updates install in place after leaving playback; Android may require allowing this app to install packages and confirming installation. Cancellation keeps the current app and data.

Enable developer options and debugging on your own TV. Use its displayed debugging/pairing settings; exact screens vary by device and Android version. For wireless debugging use `adb pair IP:PAIRING_PORT` and then `adb connect IP:DEBUG_PORT`. Install with:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
```

Open **FlixTor TV · Personal** from the TV launcher. Debug and personal-release keys differ: switching from debug to release requires uninstalling the debug build and removes its local data. Release updates reuse the personal key and preserve data.

## Account and playback

On macOS, a crash report naming `qemu-system-aarch64` and `libgfxstream_backend` identifies an emulator graphics crash rather than an Android app exception. Close the affected emulator, then cold-start it with an explicit software renderer:

```sh
"$ANDROID_HOME/emulator/emulator" -avd test_bot -gpu swiftshader -feature -Vulkan -no-snapshot-load
```

This keeps the AVD's app data. Software rendering is a diagnostic workaround, not proof that VIP playback works on physical hardware. Capture Android exceptions separately with `adb -s DEVICE_SERIAL logcat -d -b crash`. See [Android emulator graphics configuration](https://developer.android.com/studio/run/emulator-acceleration).

Account → Sign in using your phone displays the service's pairing QR. Scan on your phone, sign into the website and approve the TV. The app polls for up to three minutes. Pairing stops on leaving the screen or backgrounding. Email/password fallback submits directly to `https://flixtor.to`; passwords are never persisted. Session cookies are encrypted with an Android Keystore key, scoped by domain/path/expiry, and excluded from backup. Session cookies without server expiry are retained for at most 30 days. Sign out clears the app's local session.

All access and VIP decisions remain on the server. Session expiry or media authorization errors require signing in or retrying for a fresh stream. Network/VPN IP changes can invalidate a stream. Captions and resolution depend on the returned stream and the device decoder. No 4K/HDR guarantee. The app pauses in the background; use Play to resume on return.

Cold launch asks who's watching. Add, rename, choose an avatar, switch, or delete profiles from Profiles. Up to seven profiles share one encrypted service session. Existing library data migrates once to the default profile. My List (up to 200 titles) and viewing history (up to 100 entries) stay separate per profile. Deletion removes only that profile's library; sign-out affects the shared login. Website Sync Codes are not integrated. Image cache is bounded to 24 MB memory and 80 MB disk. Browse/search load automatically within two rows of the end. Pages are stored on disk for the active query, with up to eight decoded pages retained in memory. Stable title keys preserve focus; query/filter changes reset the grid, while returning from details restores its position. Pagination failures keep loaded titles and offer Retry.

Back on Home or the profile chooser asks for confirmation. Stay is focused by default; Back dismisses the dialog. Back inside profile management/editing and playback closes the current layer first. Exit app closes the task while retaining profiles, the encrypted session and saved viewing progress. Automatic installation waits while the exit dialog or screen-fit editor is open.

Settings offers Refresh account status. Sign-in is confirmed on `/home`; signed-in accounts then check `/account` for the service's entitlement badge. Only confirmed entitlement shows VIP active. Otherwise Signed in remains distinct, with an explanation and refresh recovery.

Settings → Display → Screen fit adjusts device-wide horizontal and vertical safe margins from 0–10%, defaulting to 5%. Preview changes apply only after Save; Cancel/Back discards them. Background artwork and video stay full-screen. Captions and controls remain within safe margins.

Settings → Playback stores Auto / up to 720p / 1080p / 2160p for the active profile. Player Settings includes the same preference, audio tracks, and subtitles. Playback stays adaptive within supported constraints. The player uses compact icon controls over a dark bottom gradient, a remote-focusable timeline, and a scrollable settings drawer. Left/Right on the timeline previews ten-second seeks; Select confirms. Back first cancels an uncommitted seek, then closes player settings, hides visible controls, and returns to detail. Media keys remain supported. Progress callbacks retain the profile/title/episode captured when playback began.

Access errors distinguish required sign-in, expired sessions, VIP restrictions, denied access, temporary service outages, unsupported decoding, and unknown response structure. Recognized access failures are not treated as broken parsers. Sign-in launched from playback retains the selected title and episode. Guest playback remains available when authorized by the service.

## Releases and problem reports

GitHub Actions verifies unit tests, lint, debug builds, and notification tests. Stable `vMAJOR.MINOR.PATCH` tags build using the existing release key and publish `app-release.apk` plus `release.json`. CI version codes are `100 + GITHUB_RUN_NUMBER`; commit SHA is embedded in the APK. Configure repository secrets `TV_KEYSTORE_BASE64`, `TV_STORE_PASSWORD`, `TV_KEY_PASSWORD`, and `TV_KEY_ALIAS`. Keys never belong in the source tree. Keep the original private key backed up.

A push to `main` verifies source but does not publish an APK. After verification, push an increasing stable version tag to run the release job. The updater installs published stable releases, not unreleased commits. A downloaded update is announced outside playback even when Android installation permission still needs to be enabled; Later keeps it available in Settings.

Maintain `incidents.json` with explicit `pending` entries containing `fingerprint`, `minVersionCode`, and `maxVersionCode`; fingerprints use the operation plus `.response-v1`, for example `player.response-v1`. A structural failure checks releases first, then this feed. Available updates take priority; an explicit matching incident shows a pending fix. Otherwise Notify Dylan sends a bounded diagnostic report with no credentials, account identifiers, viewing history, raw responses, or media URLs. An unsuccessful check says status is unavailable.

The dedicated relay lives in `notifications/`; its README covers Vercel deployment, rate limits, Resend secrets, and delivery tests. Repository variable `TV_REPORT_URL` is enabled only after deployment verification. Missing configuration displays reporting as unavailable. Personal recipient is configured server-side, never in the public APK/source. The public relay uses fixed recipients, strict payload validation, native-compatible firewall rate limits, and Resend idempotency.

## Unofficial service adapter

`FlixtorClient` owns request headers, cookies, CSRF, login and pairing. `SiteParser` owns HTML selectors and the public player response encoding. This is an unofficial integration, not an API supported by the service. Website changes may require client updates.

Observed flows:

- `GET /home`: bootstrap cookie/CSRF, shelves and account state.
- `GET /account`: authenticated entitlement badge (`.acct-status`), observed on the physical TV without exporting account identifiers or response bodies.
- `GET /show/{movies|tvshows}?ajax=1&page=...&sort=...`: catalogue HTML fragments.
- `GET /search/{query}?ajax=1&page=...`: search result HTML.
- `GET /watch/{movie|tv}/{id}/{slug}`: metadata and episode rows.
- `POST /ajax/viplogin`: the website's JSON text body with `uname` and `passw`.
- `POST /ajax/pair/new`, `/ajax/pair/poll`: cookie-bound device pairing.
- `GET /ajax/v4/m/{id}` or `/ajax/v4/e/{id}/{season}/{episode}`: media URL and tracks after server authorization.

Do not commit network captures with passwords, cookies, CSRF tokens, pairing codes or signed media URLs. The isolated live guest contract check reproduced the service’s full-server page. This is classified as a free-access capacity restriction with sign-in/retry recovery, not a structural failure. Account status distinguishes guest, signed-in with unverified VIP, explicitly verified VIP, expired, checking and unavailable states; marketing alone never proves entitlement. Catalogue/parser fixtures and public HLS playback were verified in a landscape Android 13 emulator. Caption selection was exercised; physical-device caption rendering remains unverified. Source discovery and a successful build do not establish VIP playback compatibility.

Automated coverage includes transport decoding, recognized restrictions, optional invalid captions, release metadata, profile migration/isolation/limits/deletion/quality, on-device APK checksum/version/signer rejection, and redacted report validation/provider failures. Generated candidate APKs for the on-device verifier live only under `app/build/update-test-assets/`. An in-place debug upgrade preserved profile, library, and encrypted session files. This evidence does not establish physical ONN/Chromecast installer, VIP, or sustained-playback acceptance.

The same-key signed updater was also exercised on the disposable QA emulator: it downloaded the published release and opened Android's installer confirmation. Cancel preserved the old app and data; confirming upgraded version code 9 to 105. `SignedUpgradeTest` verified a named profile, quality preference, My List, viewing progress and a decryptable fixture session before and after installation. Run this opt-in test only on disposable QA data, with `-e signedUpgradeQA true -e phase seed` before and `-e phase verify -e expectedVersionCode CODE` after. The instrumentation APK must use the same signing key as its target app.

## Device acceptance checklist

On both an ONN Google TV device and Chromecast with Google TV: browse, search, open details, return to prior focus, navigate episodes, pair VIP account, relaunch and verify session, play/seek/subtitles/quality, background/resume, watch for 30 minutes, disconnect/reconnect, and sign out. Verify invalid/expired VIP and pairing timeout. Actual hardware acceptance remains required. Optional GuestAccessTest (-e liveService true) uses a separate encrypted guest session. Optional VisualQaTest (-e visualQA true -e captureSize 1080, or 720) captures layouts using public sample media; run it only on the separate QA emulator. Screenshots are saved under the test app’s external files directory and kept outside Git.

`ExitNavigationTest` (`-e exitQA true -e captureSize 720` or `1080`) uses disposable-emulator UI fixtures to verify root confirmation, default Stay, dismissal, nested Back and deliberate exit. `AccountEvidenceTest` (`-e accountEvidence true -e expectedAccountStatus VIP`) is an explicit live account check using the device's existing session. It emits only the status category; use the expected state appropriate to the test account. Fixture captures do not establish upgraded physical-TV VIP acceptance.
