# AI Web Tab Automator

A small, Kotlin/XML-style Android app for keeping up to five visible WebView tabs and accepting **user-authorized** commands from a local Termux bridge. It targets low-memory A-series phones and avoids an Accessibility Service: automation is limited to the WebViews owned by this app.

## Important platform and website limitations

1. Android WebView has one app-wide cookie store. Separate WebView objects have separate navigation history, but Android 7–12 does not expose independent cookie jars per WebView in one process. This project does not falsely claim cookie isolation.
2. The helper uses DOM events and fixed pacing so JavaScript-heavy editors receive input events. It does **not** randomize timing or try to evade bot detection, CAPTCHAs, rate limits, login controls, or a site's terms. Do not automate a site unless its owner permits it.
3. DOM selectors and coordinate maps are site/layout-specific. A site redesign, keyboard resize, zoom, or orientation change may require running SETUP again.
4. The Activity must have a live WebView for an automation task. The foreground service queues commands while the Activity is away; it cannot magically interact with a destroyed WebView.
5. The HTTP server binds only to `127.0.0.1:8080`. It is intended for Termux on the same device, not for LAN access.

## Project layout

- `MainActivity.kt` — low-overhead tab strip, WebView lifecycle, setup overlay, command queue.
- `WebViewAutomationHelper.kt` — DOM input, coordinate click, polling, generic assistant-result extraction.
- `AutomationService.kt` — foreground service that owns the loopback server and low-frequency inbox watcher.
- `LocalHttpServer.kt` — dependency-light HTTP parser for `GET /health` and `POST /send`.
- `AppStorage.kt` — SharedPreferences JSON for mappings, routes, and queued commands.
- `BridgeRepository.kt` / `FileBridge.kt` — outbox results and optional file fallback.
- `InboxPollWorker.kt` — WorkManager fallback, minimum Android periodic interval is 15 minutes.

## Build

Open the `AIWebTabAutomator` directory in Android Studio, let Gradle sync, then run:

```bash
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

If you have generated a Gradle wrapper in Android Studio, use `./gradlew :app:assembleDebug` instead. Install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project uses minSdk 24, target/compile SDK 35, Java/Kotlin JVM 17, AndroidX WebKit, Gson, OkHttp, and WorkManager. The app-specific external-files directory is used, so it does not request broad `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions.

## First run

1. Install and launch the APK.
2. Open an allowed AI website in a tab. Add up to five tabs with `+`; long-press a tab to close it.
3. With the target page loaded, tap `SETUP`, tap the center of the text input, then tap the center of the send button. Coordinates are saved as percentages of the app's visible WebView.
4. Use `MAPS` to delete mappings or associate a host with a tab. An edit means running SETUP again on the current tab.
5. Keep the Activity/WebView alive while a task runs. Results are written as JSON files into the app's `outbox` directory.

## Termux: preferred HTTP bridge

The foreground service starts automatically. Check it from Termux:

```bash
curl -sS http://127.0.0.1:8080/health
```

Send a command with form encoding:

```bash
curl -sS -X POST http://127.0.0.1:8080/send \
  --data-urlencode 'message=List the files in the current directory' \
  --data-urlencode 'tabIndex=0'
```

Or send JSON and an optional HTTPS callback:

```bash
curl -sS -X POST http://127.0.0.1:8080/send \
  -H 'Content-Type: application/json' \
  -d '{"message":"Summarize the last command","host":"chatgpt.com","callbackUrl":"https://example.invalid/hook"}'
```

Accepted fields:

- `message` — required, max 64 KiB request body.
- `tabIndex` — optional zero-based tab number; explicit index wins.
- `host` — optional host used for a saved route or matching tab.
- `callbackUrl` — optional `http://` or `https://` URL; the result JSON is POSTed best-effort.

The HTTP response only means the command was queued. Poll the app's outbox for the final result. A successful response JSON has `success: true` and the extracted assistant text; failures include `success: false` and `error`.

## Termux: file fallback

The app watches its app-specific inbox every few seconds while the foreground service is running. The exact path is:

```bash
INBOX=/storage/emulated/0/Android/data/com.example.aiwebtabautomator/files/inbox
mkdir -p "$INBOX"
printf '%s' 'Explain this shell error' > "$INBOX/response_$(date +%s).txt"
```

Android 11+ may restrict direct Termux access to `Android/data`; if that happens, grant Termux access through its storage/SAF setup or use the HTTP bridge. Processed files are moved into `inbox/processed/`. Results appear in:

```text
/storage/emulated/0/Android/data/com.example.aiwebtabautomator/files/outbox/
```

A simple Termux helper:

```bash
send_to_ai_tab() {
  curl -fsS -X POST http://127.0.0.1:8080/send \
    --data-urlencode "message=$1" \
    --data-urlencode "tabIndex=${2:-0}"
}
send_to_ai_tab "Give a concise explanation of this error: $LAST_RESULT" 0
```

## Performance choices

- No Compose runtime, Room, or image-processing pipeline.
- One WebView per tab, maximum five; inactive WebViews are hidden and paused.
- WebView DOM storage/JavaScript are enabled only because AI sites need them; file/content access, geolocation, zoom controls, and autoplay are disabled.
- Service file polling is limited to a three-second interval only while enabled; WorkManager is a 15-minute fallback.
- WebView destruction releases the renderer when a tab is closed.
- HTTP request and outbox I/O run off the UI thread.
- Input/click failures are retried at most three times; a response timeout is not retried to avoid duplicate submissions.

## Release APK

For a signed release APK, create a signing key in Android Studio, add a private `signing.properties` file, configure `signingConfigs` in `app/build.gradle.kts`, and run:

```bash
gradle :app:assembleRelease
```

Do not commit signing keys, passwords, or callback secrets.

