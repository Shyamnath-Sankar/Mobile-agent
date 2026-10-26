# Play policy and Android audit

Every capability of ZeroClaw Mobile, whether it works on Android, how it lines up with the
Google Play Developer Program Policy (October 2026 PDF), and what the app does about it.
"Override" means an in-memory `ZEROCLAW_*` setting the app applies on every start
(`ZeroClawRuntime.androidOverrides`); the user's config file is never changed.

## The app shell

| Capability | Works on Android | Play policy | Status |
|---|---|---|---|
| Agent binary (`libzeroclaw.so`) in `jniLibs` | Yes | Native code shipped in the APK, not downloaded (Device and Network Abuse) | Kept |
| Downloaded executables / self-update | — | Banned ("may not download executable code") | Not possible: plugins forced off; Android blocks exec from writable storage; no update UI |
| Dashboard in WebView (loopback) | Yes (MATCH_PARENT fix) | No JavaScript interface added; cleartext only to 127.0.0.1 | Kept |
| Gateway pairing | Yes | "Code that introduces security vulnerabilities" not allowed | **Fixed**: pairing forced on; app pairs itself (CLI code → `/pair` → token) and hands the dashboard the token |
| Foreground service | Yes | FGS: user-perceptible, stoppable, only as long as needed | `specialUse` with notification + Stop; 3-minute grace after leaving; opt-in always-on with RAM/battery warning |
| Scheduled jobs | Yes | — | WorkManager by default; exact alarms only with user-granted `SCHEDULE_EXACT_ALARM` (not `USE_EXACT_ALARM`) |
| Battery | Yes | Must not bypass power management | No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, no wakelocks; link to the system battery list only |
| Boot restore | Yes | `RECEIVE_BOOT_COMPLETED` normal permission | Re-books the next job; restarts always-on only if the user enabled it |
| API keys | Yes (after fix) | User Data: store securely | **Fixed**: app provisions ZeroClaw's `.secret_key` (Android forbids the hard link ZeroClaw used); keys saved encrypted (`enc2:`) in app-private storage |
| AI-generated content | Yes | In-app reporting required | **Added**: App → Report an AI response; set `-PzcReportUrl` before release so reports reach you |
| Accessibility API | Not used | Banned for autonomous agents | Not used |
| Notifications | Yes | Normal runtime permission | Used for agent activity and approvals |

## Phone tools (app MCP server → `android__*`)

| Tool | Works | Policy notes | Status |
|---|---|---|---|
| `browser_navigate/snapshot/click/type/scroll/back/wait/screenshot` | Yes (WebView) | WebView JS is exempt from the executable-code rule; no `addJavascriptInterface` (Play flags it for untrusted content); no Google OAuth in WebView | Kept; submits, sign-ins, payments and secret fields need approval |
| `browser_handoff` | Yes | User signs in themselves; the agent never handles passwords | Kept; banner in the Browser tab + notification, 10-minute limit |
| Browser cookies | Yes | Stored in app-private WebView storage | Persisted (incl. third-party for SSO); "Clear data" wipes them, keeping the dashboard login |
| `web_fetch` | Yes | Network only | Kept; loopback/private networks blocked |
| `run_command` | Yes (toybox `/system/bin/sh`) | Only system binaries in the app sandbox, nothing downloaded | Kept; destructive commands and commands leaving the workspace need approval; 300 s / 32 KB limits |
| Python / Node / pip / npm | Not bundled | Runtime-loaded interpreted code must not enable violations; large binaries | Not included |

## ZeroClaw built-in tools

| Tool(s) | Works on Android | Status |
|---|---|---|
| file_read/write/edit, glob_search, content_search, memory_*, cron_*, schedule, sessions_*, delegate, spawn_subagent, llm_task, TodoWrite, ask_user, escalate_to_human, calculator, canvas, model_switch, model_routing_config, proxy_config, mcp_prompts/resources, image_info, backup | Yes (pure Rust / app sandbox) | Kept |
| web_fetch, http_request, web_search_tool, weather, pushover, git_forge, image_gen, email_read/search, poll, reaction, send_via, channel_room, send_message_to_peer | Yes (network) | Kept |
| shell | Yes (`sh` from the system) | Kept (core tool); ZeroClaw's own supervised approvals apply |
| git_operations | No `git` binary on Android | Core tool, cannot be removed; fails with "git not found" |
| screenshot | No desktop capture tool; capturing other apps would need MediaProjection | **Removed** (override) |
| browser_open, browser, browser_delegate, text_browser | Need xdg-open / Chrome / CLI browser | **Removed** (override); replaced by `android__browser_*` |
| Coding CLIs (claude-code, codex, gemini, opencode) | Not compiled into the release; no CLIs on the phone | Not available |

## ZeroClaw subsystems

| Subsystem | Works on Android | Status |
|---|---|---|
| Plugins (WASM from registry) | Not in the Android build | **Forced off** (override) — downloaded code |
| Skill bundles | Ride the plugin system | Unavailable; local `SKILL.md` / `SKILL.toml` skills work |
| Python skills | No Python | Unavailable |
| MCP stdio servers (`npx`, `uvx`) | No Node/Python | Fail to start; HTTP/SSE MCP servers work |
| Tunnels (cloudflared, ngrok, tailscale) | No binaries; would expose the gateway | **Forced off** (override) |
| Public bind / remote admin | — | **Forced off** (override); `--host 127.0.0.1` |
| Docker / Bubblewrap / Landlock runtimes | Not on Android | **Forced native** (override) |
| Hardware / peripherals (GPIO, serial) | Not on phones | Not used |
| Channels (Telegram, Discord, email, webhook) | Yes while the agent runs | Reachable in always-on mode or while the app is open |
| Cron, SOPs, memory, agents, risk profiles | Yes | Kept |

Hidden dashboard pages (theme.css): Plugins, Pairing, ACP console, and the Logout button.

## Before publishing

1. Play Console: declare the `specialUse` foreground service (text matches the manifest property).
2. Data safety: messages and files the user sends to the AI provider are shared with that provider;
   reports go to the developer endpoint; nothing else leaves the device.
3. Set `-PzcReportUrl=https://…` (an endpoint you control) for AI content reports.
4. Privacy policy URL in the listing.
5. Release signing key (the committed `app/debug.keystore` is for test builds only).
