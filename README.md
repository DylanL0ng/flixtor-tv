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

Account → Sign in using your phone displays the service's pairing QR. Scan on your phone, sign into the website and approve the TV. The app polls for up to three minutes. Pairing stops on leaving the screen or backgrounding. Email/password fallback submits directly to `https://flixtor.to`; passwords are never persisted. Session cookies are encrypted with an Android Keystore key, scoped by domain/path/expiry, and excluded from backup. Session cookies without server expiry are retained for at most 30 days. Sign out clears the app's local session.

All access and VIP decisions remain on the server. Session expiry or media authorization errors require signing in or retrying for a fresh stream. Network/VPN IP changes can invalidate a stream. Captions and resolution depend on the returned stream and the device decoder. No 4K/HDR guarantee. The app pauses in the background; use Play to resume on return.

Cold launch asks who's watching. Add, rename, choose an avatar, switch, or delete profiles from Profiles. Up to seven profiles share one encrypted service session. Existing library data migrates once to the default profile. My List (up to 200 titles) and viewing history (up to 100 entries) stay separate per profile. Deletion removes only that profile's library; sign-out affects the shared login. Website Sync Codes are not integrated. Image cache is bounded to 24 MB memory and 80 MB disk. Browse/search windows retain at most 240 titles. Load more is explicit and bounded to ten pages per browsing session.

Settings → Playback stores Auto / up to 720p / 1080p / 2160p for the active profile. Player Settings includes the same preference, audio tracks, and subtitles. Playback stays adaptive within supported constraints. Remote Back closes player settings, hides visible controls, then returns to detail. Media keys remain supported. Progress callbacks retain the profile/title/episode captured when playback began.

Access errors distinguish required sign-in, expired sessions, VIP restrictions, denied access, temporary service outages, unsupported decoding, and unknown response structure. Recognized access failures are not treated as broken parsers. Sign-in launched from playback retains the selected title and episode. Guest playback remains available when authorized by the service.

## Releases and problem reports

GitHub Actions verifies unit tests, lint, debug builds, and notification tests. Stable `vMAJOR.MINOR.PATCH` tags build using the existing release key and publish `app-release.apk` plus `release.json`. CI version codes are `100 + GITHUB_RUN_NUMBER`; commit SHA is embedded in the APK. Configure repository secrets `TV_KEYSTORE_BASE64`, `TV_STORE_PASSWORD`, `TV_KEY_PASSWORD`, and `TV_KEY_ALIAS`. Keys never belong in the source tree. Keep the original private key backed up.

Maintain `incidents.json` with explicit `pending` entries containing `fingerprint`, `minVersionCode`, and `maxVersionCode`; fingerprints use the operation plus `.response-v1`, for example `player.response-v1`. A structural failure checks releases first, then this feed. Available updates take priority; an explicit matching incident shows a pending fix. Otherwise Notify Dylan sends a bounded diagnostic report with no credentials, account identifiers, viewing history, raw responses, or media URLs. An unsuccessful check says status is unavailable.

The dedicated relay lives in `notifications/`; its README covers Vercel deployment, rate limits, Resend secrets, and delivery tests. Repository variable `TV_REPORT_URL` is enabled only after deployment verification. Missing configuration displays reporting as unavailable. Personal recipient is configured server-side, never in the public APK/source. The public relay uses fixed recipients, strict payload validation, native-compatible firewall rate limits, and Resend idempotency.

## Unofficial service adapter

`FlixtorClient` owns request headers, cookies, CSRF, login and pairing. `SiteParser` owns HTML selectors and the public player response encoding. This is an unofficial integration, not an API supported by the service. Website changes may require client updates.

Observed flows:

- `GET /home`: bootstrap cookie/CSRF, shelves and account state.
- `GET /show/{movies|tvshows}?ajax=1&page=...&sort=...`: catalogue HTML fragments.
- `GET /search/{query}?ajax=1&page=...`: search result HTML.
- `GET /watch/{movie|tv}/{id}/{slug}`: metadata and episode rows.
- `POST /ajax/viplogin`: the website's JSON text body with `uname` and `passw`.
- `POST /ajax/pair/new`, `/ajax/pair/poll`: cookie-bound device pairing.
- `GET /ajax/v4/m/{id}` or `/ajax/v4/e/{id}/{season}/{episode}`: media URL and tracks after server authorization.

Do not commit network captures with passwords, cookies, CSRF tokens, pairing codes or signed media URLs. No account credentials or VIP playback were available during implementation. Public catalogue browsing and one public HLS video with subtitles were verified in a landscape Android 13 emulator. Source discovery and a successful build do not establish VIP playback compatibility.

Automated coverage includes transport decoding, recognized restrictions, optional invalid captions, release metadata, profile migration/isolation/limits/deletion/quality, on-device APK checksum/version/signer rejection, and redacted report validation/provider failures. Generated candidate APKs for the on-device verifier live only under `app/build/update-test-assets/`. An in-place debug upgrade preserved profile, library, and encrypted session files. This evidence does not establish physical ONN/Chromecast installer, VIP, or sustained-playback acceptance.

## Device acceptance checklist

On both an ONN Google TV device and Chromecast with Google TV: browse, search, open details, return to prior focus, navigate episodes, pair VIP account, relaunch and verify session, play/seek/subtitles/quality, background/resume, watch for 30 minutes, disconnect/reconnect, and sign out. Verify invalid/expired VIP and pairing timeout. Actual hardware acceptance remains required.
