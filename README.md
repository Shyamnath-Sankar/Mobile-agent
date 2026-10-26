# ZeroClaw Mobile

Android app that runs the [ZeroClaw](https://github.com/zeroclaw-labs/zeroclaw) agent on-device
and shows its dashboard in-app. First build is a feasibility spike (arm64 only).

## Get the APK

1. Open the repo's **Actions** tab, pick the latest **Android APK** run.
2. Download the `zeroclaw-mobile-debug-apk` artifact, unzip, install `app-debug.apk`
   (allow "install unknown apps" for your browser/files app).

## Use it

1. **Control** tab, **Start agent** (allow notifications so the foreground notification shows).
2. When the gateway is up, the **Dashboard** tab opens ZeroClaw's web UI (pairing is switched off). Add your provider and API key there.
3. If something fails, use **Copy all** in Logs and share the output.

See `CLAUDE.md` for architecture and rules.
