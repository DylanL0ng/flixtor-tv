# Personal FlixTor TV client

Native Kotlin / Compose for TV app. Catalogue, search, details and seasons, phone QR pairing, email/password sign-in, local My List, Continue Watching, and Media3 HLS playback with subtitles and video-quality controls. No WebView.

Targets ONN **Android/Google TV** devices and Chromecast **with Google TV** on Android 10+. ONN Roku TVs and classic cast-only Chromecast devices cannot run this APK. Sky IP061 (Sky Stream) has no supported APK sideloading path.

## Build

Install JDK 17 and Android SDK platform/build-tools 34. Create untracked `local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 tools/build_release.py
```

Set `JAVA_HOME` to JDK 17 if your default Java differs. The release helper creates a private signing key and config in `~/.local/share/flixtor-tv/`. Keep a secure backup: updates need the same key. Signing keys and passwords never belong in Git. Advanced builds can supply `TV_KEYSTORE`, `TV_STORE_PASSWORD`, `TV_KEY_ALIAS`, and `TV_KEY_PASSWORD` directly to Gradle.

## Install and update

Enable developer options and debugging on your own TV. Use its displayed debugging/pairing settings; exact screens vary by device and Android version. For wireless debugging use `adb pair IP:PAIRING_PORT` and then `adb connect IP:DEBUG_PORT`. Install with:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
```

Open **FlixTor TV · Personal** from the TV launcher. Debug and personal-release keys differ: switching from debug to release requires uninstalling the debug build and removes its local data. Release updates reuse the personal key and preserve data.

## Account and playback

Account → Sign in using your phone displays the service's pairing QR. Scan on your phone, sign into the website and approve the TV. The app polls for up to three minutes. Pairing stops on leaving the screen or backgrounding. Email/password fallback submits directly to `https://flixtor.to`; passwords are never persisted. Session cookies are encrypted with an Android Keystore key, scoped by domain/path/expiry, and excluded from backup. Session cookies without server expiry are retained for at most 30 days. Sign out clears the app's local session.

All access and VIP decisions remain on the server. Session expiry or media authorization errors require signing in or retrying for a fresh stream. Network/VPN IP changes can invalidate a stream. Captions and resolution depend on the returned stream and the device decoder. No 4K/HDR guarantee. The app pauses in the background; use Play to resume on return.

My List (up to 200 titles) and viewing history (up to 100 entries) stay on the device. Website Sync Codes are not integrated. Image cache is bounded to 24 MB memory and 80 MB disk. Browse/search windows retain at most 240 titles. Load more is explicit and bounded to ten pages per browsing session.

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

## Device acceptance checklist

On both an ONN Google TV device and Chromecast with Google TV: browse, search, open details, return to prior focus, navigate episodes, pair VIP account, relaunch and verify session, play/seek/subtitles/quality, background/resume, watch for 30 minutes, disconnect/reconnect, and sign out. Verify invalid/expired VIP and pairing timeout. Actual hardware acceptance remains required.
