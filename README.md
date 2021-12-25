# CodeAssist
[![stability-alpha](https://img.shields.io/badge/stability-alpha-f4d03f.svg)](https://github.com/mkenney/software-guides/blob/master/STABILITY-BADGES.md#alpha)
[![Chat](https://img.shields.io/badge/chat-on%20discord-7289da)](https://discord.gg/pffnyE6prs)

A javac APIs-based code editor that supports building Android apps.

## Note
CodeAssist does not use gradle. Editing the build.gradle file will not do anything. There are currently no plans with using Gradle.

## Features
- [x] APK Compilation
- [x] AAB Support
- [x] Java
- [x] Kotlin  
- [x] R8/ProGuard
- [x] Code Completions (Currently for Java only)  
- [x] Quick fixes (Import missing class and Implement Abstract Methods)  
- [x] Layout Preview (80%)
- [x] Automatic dependency resolution  
- [ ] Layout Editor
- [ ] Debugger
- [ ] Lint 

## Installation

CodeAssist can be obtained through various sources listed below.
The APK files of different sources are signed with different signature keys.
If you wish to install from a different source, then you must **uninstall CodeAssist** from your device first, then install new APKs from the new source.

### Github

CodeAssist application can be obtained on `Github` either from [`Github Releases`](https://github.com/tyron12233/CodeAssist/releases) or from [`Github Build`](https://github.com/tyron12233/CodeAssist/actions/workflows/build-apk.yml) action workflows.

The APKs for `Github Releases` will be listed under `Assets` drop-down of a release. These are automatically attached when a new version is released.

The APKs for `Github Build` action workflows will be listed under `Artifacts` section of a workflow run. These are created for each commit/push done to the repository and can be used by users who don't want to wait for releases and want to try out the latest features immediately or want to test their pull requests.

### Google Play Store

CodeAssist application can be obtained from `Google Play Store`

<a href='https://play.google.com/store/apps/details?id=com.tyron.code'><img alt='Get it on Google Play' width="250px" src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>

## CodeAssist Community
Discord server: https://discord.gg/3YMZkgFS

English-language chat in Telegram: https://t.me/codeassist_app

Russian-language (русскоязычный) chat in Telegram: https://t.me/codeassist_chat

## Issues
If you have any problems, then immediately write about them. This is very important for the next versions of CodeAssist

## Building - Android Studio
Clone this repository to your local device and then open it on Android Studio.

## Contributing
- Pull request must have a short description as a title and a more detailed one in the description
- Feature additions must include Unit/Instrumentation tests. This is for future stability of the app and modules.

# Special thanks
- Rosemoe/CodeEditor 
- JavaNIDE
- Mike Anderson
- Java Language Server
- Ilyasse Salama
