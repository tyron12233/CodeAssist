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

## CodeAssist Community
Discord server: https://discord.gg/3YMZkgFS

English-language chat in Telegram: https://t.me/codeassist_app

Russian-language (русскоязычный) chat in Telegram: https://t.me/codeassist_chat

## Issues
Issues are disabled until the next release as most issues has been fixed and there have been many changes so the issues on the previous apk may not be valid with the current apk.

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
