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
- `ZeroClawRuntime` owns the child process, `ZeroClawService` is the dataSync foreground service,
  `WebAssets` extracts the dashboard bundle, `ui/ZeroClawApp.kt` has the Agent (dashboard WebView)/Logs/App tabs.
- The dashboard WebView must have MATCH_PARENT layoutParams: Compose's default WRAP_CONTENT breaks the
  CSS viewport height (`vh`) and the dashboard renders blank. Keep it alive across tabs (don't recreate it).
- Env passed to the process: `HOME`, `TMPDIR`, `ZEROCLAW_CONFIG_DIR`, `ZEROCLAW_gateway__web_dist_dir`.
- Release builds are arm64-v8a only (ZeroClaw publishes no x86_64 Android binary), so the
  emulator needs an arm64 image or use a real device.

## Hard rules (Play-policy safe)

- No AccessibilityService for automation.
- No downloading `.so`/dex or any executable code at runtime. Native binaries only in `jniLibs`.
  (Model weights such as GGUF are data and may be downloaded.)
- API keys in Android Keystore (EncryptedSharedPreferences/Keystore), never in plain prefs or logs.
- OAuth only via Chrome Custom Tabs. WebView is allowed **only** for the local agent
  dashboard / agent browser, never for sign-in.
- Keep permissions minimal; every sensitive permission needs an in-app disclosure.
- Gateway stays bound to loopback; never expose it on the network.
- TODO before any public release: the gateway runs with `require_pairing=false` (so any local app can
  reach 127.0.0.1:42617). Replace with automatic pairing (app obtains the token and hands it to the
  dashboard) or another per-app authentication.

## Stack (target)

Hilt, Room, WorkManager, kotlinx.serialization, a foreground service of type `dataSync` for running
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
