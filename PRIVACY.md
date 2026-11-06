# Privacy Policy

**App:** CodeAssist
**Effective date:** 2 November 2026

This policy explains what information the CodeAssist Android app handles, why, and the choices you have. CodeAssist is a code editor and build environment that runs on your device. You can use the editor and build tools without an account. Some online features (the Projects Store, daily challenges, and CodeAssist Free AI) need you to sign in.

## Summary

- Your projects stay on your device. The app sends your code or files somewhere only when you use a feature that needs to: asking the AI assistant, submitting a daily-challenge solution, or publishing a project to the Projects Store.
- Signing in is optional and uses your GitHub or Google account. It is needed only for the Projects Store, daily challenges, and CodeAssist Free AI.
- The app shows ads from Google AdMob and its partner ad networks. Ads use your device's advertising ID, subject to the consent choices offered where the law requires them.
- Performance, crash, and feature-usage telemetry is off by default and is sent only if you turn it on.
- We do not sell your data.

## Information the app handles

### Your projects and files

Projects, files, settings, and signing keys are stored on your device. They leave your device only in these cases, each started by you:

- **AI assistant:** see "AI assistant" below.
- **Daily challenges:** when you submit a solution, its source code is sent to our judging server to be compiled, run against tests, and scored.
- **Projects Store submissions:** when you publish a project, the app packages it (excluding build output, `.git`, keystores, and `local.properties`), shows you the file list, and uploads it for review. Approved projects, their screenshots, and their icon become public.
- **Version control:** when you push to or pull from a Git remote, the app talks to the remote you configured, using the credentials you gave it.

### Account (optional)

If you sign in with GitHub or Google, our backend (Supabase) receives the account identifier, email address, display name, and profile picture that the provider shares. We use them to keep you signed in and to show your publisher profile (handle, display name, avatar, and any bio or link you add) next to projects you publish. Your sign-in session is stored on your device.

Signed-in activity is stored with your account: projects and reviews you publish, ratings, likes, follows, daily-challenge submissions and scores (shown on a public leaderboard), and your CodeAssist Free AI points and usage.

### AI assistant

The AI assistant sends the conversation to an AI provider so that it can answer. That includes your messages, any files, code selections, or images you attach, and the results of the actions the assistant takes in your project (for example the contents of a file it opens, or a build's output).

- **CodeAssist Free AI:** requests go through our server to Anthropic, which runs the Claude models. Our server checks your sign-in, applies usage limits, and records each request's model, token counts, and cost against your points. It does not store the content of your conversations. Anthropic processes the content under its commercial terms, which do not allow it to be used to train Anthropic's models. See https://www.anthropic.com/legal/privacy.
- **Your own API key:** if you add a key for Anthropic, OpenAI, Google Gemini, OpenRouter, or a custom endpoint, the app sends requests straight to that provider. They do not pass through our servers, and the provider's own terms and privacy policy apply. Keys are stored on your device.
- **Saved conversations** are stored on your device.

### Rewarded ads for AI points

You can choose to watch a rewarded ad to earn CodeAssist Free AI points. When you do, the app gives Google your CodeAssist account identifier with the ad, and Google sends a signed confirmation to our server when you have earned the reward. Our server records the ad's transaction identifier and credits your points. These ads are shown only when you tap to watch one.

### Ads

The app shows native, banner, and occasional full-screen ads from Google AdMob. To fill them, AdMob may use partner networks: Meta Audience Network, Pangle, Mintegral, and Unity Ads. These services may collect your device's advertising ID, IP address, and information about the device and the ads you see and tap, and use it to show and measure ads, and, where you have agreed, to personalize them. In the European Economic Area, the United Kingdom, and Switzerland, the app asks for your consent through Google's consent form before personalized ads are requested, and you can change your choice later in Settings, Privacy and data.

You can turn the app's passive ads off in Settings at any time. You can also reset or delete your advertising ID in your device's settings. See Google's policy at https://policies.google.com/technologies/ads.

### Push notifications

The app registers with Firebase Cloud Messaging to receive notifications about the Projects Store (for example when a project you submitted is reviewed). Our backend stores the device's notification token, a random install identifier, the app version, and, if you are signed in, your account, so a notification can reach you. You can turn notifications off in Android's settings.

### Optional telemetry (off by default)

On first launch the app asks whether you want to share anonymous usage data. Nothing is collected unless you agree. You can change your choice later in the app.

If you turn it on, the app sends:

- **App and device information:** app version and build number, Android API level, device model and manufacturer, CPU architecture, and device language.
- **Performance measurements:** how long startup, indexing, builds, code completion, code analysis, and previews take, often as aggregated summaries.
- **Feature usage:** how many times each feature is used (for example the preview, quick fixes, the AI assistant, or Git commits), counted on the device and sent as one total per feature. Only fixed feature names are sent, never what you typed or which file you were in.
- **Build outcomes:** whether a build or run succeeded or failed, and how long it took.
- **Ad events:** when a full-screen ad is shown or skipped and when you change ad settings. These describe how often ads appear, not what they were, and are not shared with ad networks.
- **Crash and error reports:** the exception type and the app's own stack frames. Messages, file paths, and code are removed before the report is created.
- **Identifiers:** a random install identifier generated on your device and a per-session identifier.

Telemetry never includes your source code, file contents, file names, project names, or file paths.

## Permissions the app requests

- **Internet:** for the features above, downloading dependencies and SDK components, ads, and optional telemetry.
- **Install unknown apps:** so you can install an app you built with CodeAssist, through Android's standard install confirmation.
- **Notifications:** for build progress and Projects Store notifications.
- **Foreground service:** so a long build can keep running while you switch apps.
- **Advertising ID:** added by the Google Mobile Ads SDK, for ads as described above.

## Other network connections

When you ask the app to, it downloads project dependencies from package repositories (for example Maven Central and Google's Maven repository) and Android SDK components from Google's servers. These requests contain the package names and versions being fetched, not your code.

## How the information is used

- To provide the features you use: sign-in, the Projects Store, daily challenges, the AI assistant, and notifications.
- To run CodeAssist Free AI fairly: to keep each account within its points and daily limits and the service within its monthly budget, and to prevent abuse.
- To show ads, which pay for the app and for CodeAssist Free AI.
- With your permission, to understand and improve performance and stability.

We do not sell your data and do not use it to build advertising profiles of our own.

## Who receives it

- **Supabase** hosts our database, sign-in, file storage, and server functions.
- **Vercel** hosts the daily-challenge judge.
- **Cloudflare R2** serves public Projects Store files.
- **Anthropic** runs the models behind CodeAssist Free AI.
- **Google (Firebase Cloud Messaging, AdMob)** and the ad partners listed above.
- **GitHub or Google**, when you sign in with them.
- The **AI provider** you choose, when you use your own API key, and the **Git remote** you configure.

Each acts under its own terms for its part of the service. We may also disclose information if the law requires it.

## Data retention and deletion

- Data on your device stays until you delete it or uninstall the app.
- Raw telemetry events are kept for 7 days; after that only daily totals remain, which cannot be linked to an install.
- Account data, published projects, reviews, challenge results, and AI points and usage records are kept while your account exists.
- Conversation content sent through CodeAssist Free AI is not stored on our servers. Anthropic's own retention is described in its privacy policy.

To have telemetry removed, send the install identifier shown in the app's analytics settings to the address below.

## Deleting your account

You can delete your CodeAssist account at any time:

1. In the app, open the **Store** tab and tap your profile picture to open **You**.
2. Scroll to the bottom and tap **Delete account**, then confirm.

This permanently deletes the account and everything stored with it: your publisher profile, the projects you published and their files, your reviews, ratings, likes and follows, your daily-challenge results, your CodeAssist Free AI points and usage records, and your push-notification registration. Projects on your device are not affected. Deletion happens immediately and cannot be undone.

If you can no longer open the app, email **contact.tyronscott@gmail.com** from the email address of the GitHub or Google account you signed in with, or naming that account, and ask for your account to be deleted. We will delete it within 30 days and confirm by email.

## Security

All connections to our servers use HTTPS. Access to stored data is restricted by database access rules, and AI provider keys for CodeAssist Free AI are held on our servers, never in the app.

## Children

CodeAssist is a developer tool and is not directed to children under 13. It does not knowingly collect personal information from children under 13. If you believe a child has created an account, contact us and we will delete it.

## Changes to this policy

This policy may be updated as the app changes. Material changes will be reflected here with a new effective date.

## Contact

Questions about this policy, or requests to access or delete your data, can be sent to:

**contact.tyronscott@gmail.com**

The app's source code is available at https://github.com/tyron12233/CodeAssist
