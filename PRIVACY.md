# Privacy Policy

**App:** CodePrimer, a remix of [CodeAssist](https://github.com/tyron12233/CodeAssist) by tyron12233
**Effective date:** 30 October 2026

CodePrimer is a code editor and build environment that runs on your device. You do not need an account to use it.
This policy is written for the CodePrimer build published from https://github.com/veelex/CodePrimer. It is not the
policy of the official CodeAssist app, which has its own.

## Summary

- CodePrimer does not ask you to sign in and does not ask for your name, email, phone number, or location.
- The app does not read, upload, or share the source code, files, or projects you work on. Your projects stay on your device.
- This build is configured **without a telemetry server**. The app contains the code for CodeAssist's optional
  telemetry prompt, but with no server configured nothing is sent, whatever you choose.

## Network connections

CodePrimer connects to the internet to carry out actions you start, such as resolving and downloading project
dependencies from package repositories (for example Maven repositories) or downloading Android SDK components, sources,
and documentation. These requests contain the package names and versions being fetched. They do not contain your code.
Downloaded files are cached on your device.

## Ads

The app includes Google's AdMob SDK and, in this build, shows Google's test ads. Google's SDK may collect and use device
identifiers and other data under [Google's own policies](https://policies.google.com/privacy), which this app does not
control. Ads can be turned off in the app's settings.

## Permissions the app requests

- **Internet:** used to download project dependencies and Android SDK components when you ask for them, and by the ads SDK.
- **Install unknown apps (REQUEST_INSTALL_PACKAGES):** used so you can install an app (APK) that you built with CodePrimer.
  This is initiated by you and goes through the standard Android install confirmation.

## Data on your device

Local project data, settings, and caches remain on your device until you remove them or uninstall the app. Project data
and credentials you use inside the app (for example signing keys) are stored on your device and are not transmitted by it.

## Children

CodePrimer is a developer tool and is not directed to children under 13. It does not knowingly collect personal
information from children.

## Changes to this policy

This policy may be updated as the app changes. Material changes will be reflected here with a new effective date.

## Contact

Questions about this policy can be raised at https://github.com/veelex/CodePrimer/issues

The app's source code is available at https://github.com/veelex/CodePrimer
