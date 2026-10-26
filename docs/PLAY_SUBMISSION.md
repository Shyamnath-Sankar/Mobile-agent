# Play Console submission notes

Everything Play Console asks for, matched to how the app actually behaves. Review before submitting;
nothing here is legal advice. See `docs/PLAY_AUDIT.md` for the feature-by-feature policy audit.

## Privacy policy URL

Play needs a public URL. The policy lives in `docs/legal/privacy-policy.md` (the same file the app shows).
Easiest hosting: GitHub Pages on this repo (Settings → Pages → deploy from branch, folder `/docs`), giving
`https://shyamnath-sankar.github.io/Mobile-agent/legal/privacy-policy`. Before publishing, add a contact
email to the policy's "Who we are" section and on the Play listing.

## Data safety form

The developer receives no user data except AI-content reports. Data the user sends to their chosen AI
provider is sent directly from the phone with the user's own API key.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all user data encrypted in transit? | Yes (HTTPS to providers and websites; the only cleartext traffic is loopback inside the phone) |
| Do you provide a way for users to request that their data be deleted? | Yes: data is on the device; deleting it in the app or uninstalling removes it. Reports: via the contact email |

Data types (declare as **shared** with the AI provider the user configures, for **App functionality**;
processed to provide the feature, not used for ads or analytics):

| Data type | Collected | Shared | Purpose | Optional? |
|---|---|---|---|---|
| Messages → Other in-app messages (prompts and chat context) | Yes | Yes (user's AI provider) | App functionality | Required for chat |
| Files and docs (files the user attaches or the agent reads in its workspace) | Yes | Yes (user's AI provider) | App functionality | Optional |
| Photos (images the user attaches) | Yes | Yes (user's AI provider) | App functionality | Optional |
| App activity → Other user-generated content (AI-content reports) | Yes | No | App functionality (safety) | Optional |

Not collected: location, contacts, calendar, SMS, call logs, health, financial info, device IDs, analytics, crash logs.

## Foreground service declaration (App content → Foreground services)

- **Type:** `specialUse` (subtype text is in the manifest property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`).
- **Description to paste:** "ZeroClaw Mobile runs an on-device AI agent that the user starts. When the user
  leaves the app while the agent is working, a foreground service keeps the task running for up to 3 minutes
  and then stops itself. Users can optionally turn on 'Always on' (after a disclosure of RAM and battery
  cost) so chat channels they connected can reach the agent. A notification with a Stop action is always
  shown. Scheduled jobs do not use the service; they use WorkManager."
- **User impact if deferred:** "A task the user just started (e.g. a web research or file job) would be
  killed mid-way and its result lost; in always-on mode, messages from the user's own chat channels would
  not be answered."
- **Video:** record: start a chat task → press Home → show the notification → it stops after 3 minutes;
  then App → Always on → disclosure dialog → notification with Stop.

## Other declarations

- **Exact alarms:** the app declares `SCHEDULE_EXACT_ALARM` (user-granted, off by default), not `USE_EXACT_ALARM`. No declaration form needed.
- **Ads:** No ads.
- **App access:** No login required. Reviewers need an AI provider API key to chat: provide a test key in
  "App access" instructions (Play asks for full access to AI features).
- **Content rating:** answer the questionnaire as a general productivity/utility app with user-generated
  AI content; expect a "Users interact" / "Unrestricted internet" note.
- **Target audience:** 18+ (or 13+); not designed for children.
- **AI-generated content:** in-app reporting is in App → Report an AI response. Set the report endpoint
  (`-PzcReportUrl=...`) so reports reach you.
- **Government / financial / health:** not applicable.

## Closed testing

- New personal developer accounts must run a closed test with **at least 12 testers for 14 consecutive
  days** before applying for production access.
- Upload an **AAB** signed with your upload key (Play App Signing). The current CI builds a debug APK
  for device testing only; release signing will be added when you are ready.
- Use an email-list testers track; share the opt-in link; keep the test running 14 days with active testers.

## Release checklist

- [ ] 16 KB-aligned `libzeroclaw.so` (CI verifies on every build)
- [ ] Privacy policy URL live and contact email filled in
- [ ] Report endpoint configured
- [ ] Data safety, FGS, content rating, target audience, app access filled in
- [ ] Release signing + AAB
- [ ] Store listing: icon 512×512, feature graphic 1024×500, at least 2 phone screenshots, short and full description
