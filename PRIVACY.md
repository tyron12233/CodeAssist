# Privacy Policy

**App:** CodeAssist
**Effective date:** 20 June 2026

This policy explains what information the CodeAssist Android app handles, why, and the choices you have. CodeAssist is a code editor and build environment that runs on your device. You do not need an account to use it.

## Summary

- CodeAssist does not require you to sign in and does not ask for your name, email, phone number, or location.
- The app does not read, upload, or share the source code, files, or projects you work on. Your projects stay on your device.
- The only data the app sends about you is optional performance and crash telemetry. It is turned off by default and is sent only if you choose to turn it on.
- You can turn telemetry on or off at any time in the app.

## Information the app collects

### Optional performance and stability telemetry (off by default)

On first launch the app shows a prompt asking whether you want to share anonymous performance data. Nothing is collected unless you tap "Allow". You can change your choice later from the project picker.

If you turn it on, the app sends:

- **App and device information:** app version and build number, Android API level, device model and manufacturer, CPU architecture (ABI), and device language (locale).
- **Performance measurements:** how long app startup, project indexing, builds, code completion, and code analysis take, reported as durations and as aggregated summaries (for example a count and average over a period), not as a record of individual keystrokes.
- **Build outcomes:** whether a build or run succeeded or failed, and how long it took.
- **Crash and error reports:** a scrubbed report when the app crashes or hits an internal error. The report contains the exception type and the app's own stack frames only. Exception messages, file paths, and any code are removed before the report is created.
- **Identifiers:** a random install identifier (a UUID generated on your device that is not linked to your identity or any account) and a per-session identifier used to group events from one app launch.

### What the app never collects

- Your source code, file contents, file names, or project names.
- File system paths.
- Which features you use (there is no feature-usage tracking).
- Advertising identifiers, device serial numbers, IMEI, account information, contacts, or precise location.
- Anything that identifies you personally.

If you do not turn on telemetry, none of the above leaves your device. Turning telemetry off also discards any data that was waiting to be sent.

## Permissions the app requests

- **Internet:** used to download project dependencies and Android SDK components when you ask for them, and to send optional telemetry if you have turned it on.
- **Install unknown apps (REQUEST_INSTALL_PACKAGES):** used so you can install an app (APK) that you built with CodeAssist. This is initiated by you and goes through the standard Android install confirmation.

## Network connections

Apart from optional telemetry, CodeAssist connects to the internet only to carry out actions you start, such as resolving and downloading project dependencies from package repositories (for example Maven repositories) or downloading Android SDK components, sources, and documentation. These requests contain the package names and versions being fetched. They do not contain your code. Downloaded files are cached on your device.

## How the information is used

Telemetry is used only to understand and improve the app's performance and stability, for example to find slow operations and to diagnose crashes. It is not used for advertising, profiling, or sale.

## How the information is shared

CodeAssist does not sell your data and does not share it with advertisers.

Optional telemetry is stored using Supabase, a hosted database service, acting as a data processor for the app. Telemetry is transmitted over an encrypted connection. No other third party receives telemetry.

When you download dependencies or SDK components, those requests go directly to the relevant package repositories or to Google's Android SDK servers, which handle them under their own terms and policies.

## Data retention and deletion

- Local project data, settings, and caches remain on your device until you remove them or uninstall the app. Uninstalling removes the app's local data.
- Telemetry, if you turned it on, is retained on the telemetry backend for performance and stability analysis. Because telemetry carries only a random install identifier and contains no personal information, individual records cannot be tied back to a specific person.
- To stop all future collection, turn off telemetry in the app. If you want telemetry already sent under your install identifier to be removed, contact us at the address below and include the install identifier shown in the app's analytics settings.

## Security

Telemetry is sent over HTTPS. Project data and credentials you use inside CodeAssist (for example signing keys) are stored on your device and are not transmitted by the app.

## Children

CodeAssist is a developer tool and is not directed to children under 13. It does not knowingly collect personal information from children.

## Changes to this policy

This policy may be updated as the app changes. Material changes will be reflected here with a new effective date.

## Contact

Questions about this policy or your data can be sent to:

**contact.tyronscott@gmail.com**

The app's source code is available at https://github.com/tyron12233/CodeAssist
