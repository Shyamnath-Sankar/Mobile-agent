# Mobile-agent (ZeroClaw Mobile)

Native Android AI agent. Kotlin + Jetpack Compose, minSdk 26, targetSdk 36.
The agent core is **ZeroClaw** (Rust, MIT/Apache-2.0). LLMs are cloud APIs (OpenAI-compatible,
Anthropic, Gemini, OpenRouter) plus an optional on-device model via llama.cpp (GGUF, downloaded
after install). Everything else runs locally on the device.

## How ZeroClaw is embedded (current)

ZeroClaw is a daemon/CLI, not a library (no FFI). We ship the **official prebuilt
`aarch64-linux-android` release binary** as `app/src/main/jniLibs/arm64-v8a/libzeroclaw.so`
(extractNativeLibs=true, so it lands in `nativeLibraryDir` and can be exec'd), run it as
`zeroclaw daemon --host 127.0.0.1 -p 42617` from a foreground service, and show its built-in
web dashboard in a WebView on loopback. Kotlin-side Android tools will later be exposed to it
as a local MCP server. A UniFFI facade crate is a possible later step, not the current plan.

- `scripts/fetch-zeroclaw.sh` downloads the release (set `ZEROCLAW_TAG` to pin) and stages the
  binary + `web/dist` dashboard. These are gitignored, never committed.
- `ZeroClawRuntime` owns the child process, `ZeroClawService` is the specialUse foreground service (see Background),
  `WebAssets` extracts the dashboard bundle, `ui/ZeroClawApp.kt` has the Agent (dashboard WebView)/Logs/App tabs.
- The dashboard WebView must have MATCH_PARENT layoutParams: Compose's default WRAP_CONTENT breaks the
  CSS viewport height (`vh`) and the dashboard renders blank. Keep it alive across tabs (don't recreate it).
- Env passed to the process: `HOME`, `TMPDIR`, `ZEROCLAW_CONFIG_DIR`, `ZEROCLAW_gateway__web_dist_dir`.
- Release builds are arm64-v8a only (ZeroClaw publishes no x86_64 Android binary), so the
  emulator needs an arm64 image or use a real device.

## Background and schedules (Play FGS policy: run only as long as needed, user-visible, stoppable)

`background/AgentController` runs the daemon only while something holds it: `Ui` (app on screen, no
FGS), `Grace` (3-minute "finishing up" specialUse FGS started from `onPause`), `Worker` (due cron jobs)
or `AlwaysOn` (opt-in persistent specialUse FGS). ZeroClaw's cron is the source of truth: `AgentScheduler`
reads `GET /api/cron`, books the earliest `next_run` via WorkManager (default, battery-friendly) or an exact
alarm (opt-in, user-granted SCHEDULE_EXACT_ALARM; never USE_EXACT_ALARM), and `ScheduledRunWorker`
starts the agent, waits until due jobs' `last_run` passes their due time (max 9 min), re-books, releases.
No wakelocks, no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (only a link to the battery settings list).

## Phone tools (MCP)

`tools/McpServer` is a Streamable-HTTP MCP server on 127.0.0.1:42618 (per-install token in the URL).
`McpRegistrar` registers it through the gateway config API as `mcp.servers.android` (http, tool timeout 330 s),
bundle `mcp_bundles.phone`, granted to every agent; ZeroClaw sees the tools as `android__<name>`.
Tools (`tools/Tools.kt`): browser_navigate/snapshot/click/type/scroll/back/wait/screenshot/handoff (one shared WebView driven only via
evaluateJavascript, never addJavascriptInterface; live in the Browser tab), web_fetch, run_command
(`/system/bin/sh` in `files/workspace`, no downloads). `UrlGuard` blocks loopback/private hosts.
Risky actions (submits, sign-ins, payments, secret fields, destructive commands) go through `Approvals`
(in-app dialog + notification, deny on 2-minute timeout).

## Design

Sage paper (#DDE1DA), ink type (#0F100D), Instrument Serif for display, Manrope for UI, pastel
accents (lilac, mint, forest, butter, apricot), round ink arrow buttons. Native tokens live in
`ui/theme/Theme.kt`; the dashboard is themed by `assets/zc-theme/theme.css` (overrides its `--pc-*`
tokens with `!important`, served at `/_app/zc-theme/`, linked into `index.html` by `WebAssets`).
Keep both in sync. Fonts are OFL (licences in `assets/zc-theme/`).

## Hard rules (Play-policy safe)

- No AccessibilityService for automation.
- No downloading `.so`/dex or any executable code at runtime. Native binaries only in `jniLibs`.
  (Model weights such as GGUF are data and may be downloaded.)
- API keys in Android Keystore (EncryptedSharedPreferences/Keystore), never in plain prefs or logs.
- OAuth only via Chrome Custom Tabs. WebView is allowed **only** for the local agent
  dashboard / agent browser, never for sign-in.
- Keep permissions minimal; every sensitive permission needs an in-app disclosure.
- Gateway stays bound to loopback; never expose it on the network.
- Gateway pairing stays on. `GatewayAuth` pairs automatically (CLI `gateway get-paircode` → `POST /pair`),
  sends the bearer token on app API calls and injects it as `localStorage.zeroclaw_token` for the dashboard.
- Android-incompatible or non-compliant ZeroClaw features are switched off with in-memory env overrides
  (`ZeroClawRuntime.androidOverrides`). Keep `docs/PLAY_AUDIT.md` in sync when adding features.

## Stack (target)

Hilt, Room, WorkManager, kotlinx.serialization, a foreground service of type `specialUse` for running
tasks. The current spike uses none of these yet (single `app` module) and will be split into
modules (core-agent, feature-chat, feature-browser, feature-terminal, feature-files, data) later.

## Build and test

- The cloud sandbox cannot reach `dl.google.com`, so the Android SDK/NDK is unavailable there.
  Builds run in GitHub Actions (`.github/workflows/android.yml`); download the APK from the run's artifacts.
- Local: `bash scripts/fetch-zeroclaw.sh && ./gradlew assembleDebug` (needs JDK 17 + Android SDK 36).
- Always build and run tests after changes; check the Actions run before calling anything done.
- Do not claim the app works until it has been run on a device; say what was and was not verified.

## Roadmap

The original 12 prompts remain the plan (skeleton, Rust core, models, chat UI, local model,
files + memory, agent browser, sign-in browser, terminal, coding workspace, schedules,
Play readiness), one at a time, tested before moving on. Terminal/interpreters and the
battery-optimisation exemption carry the most Play review risk and come last.
