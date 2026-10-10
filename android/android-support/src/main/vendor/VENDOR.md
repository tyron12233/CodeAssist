# Vendored: profgen, the Baseline Profile compiler

These files are AGP's `profgen` (the code behind its `compile<Variant>ArtProfile` task), copied byte for byte
with their package left alone (`com.android.tools.profgen`) so that a re-sync is an overwrite. The build uses
them to turn a project's `baseline-prof.txt` rules into the binary `baseline.prof` / `baseline.profm` that ART
and `androidx.profileinstaller` read; see `tools/ArtProfiles.kt`.

    upstream:  AOSP platform/tools/base, profgen/profgen/src/main/kotlin
    taken from: com.android.tools.build:gradle:9.2.1, gradle-9.2.1-sources.jar
                (sha256 2b6fbf6fa80642759fa0ac32ccc6bfae2aa1d9ce27051162635dcae36eb5adcc)
    licence:   Apache 2.0 (LICENSE.txt in this directory)

## Why vendored rather than depended on

profgen is not published on its own. AGP shades it into the Gradle plugin jar, and depending on the plugin
(tens of megabytes, built for a Gradle daemon) to reach a dozen small files is not an option for something that
runs on the phone. Its only dependencies are the stdlib and ASM, which this module already has.

## What was left out

Two upstream files are not copied, both command-line helpers that the build never calls:

- `ProfgenUtils.kt` (`expandWildcards(String, ...)`, `readHumanReadableProfileOrExit`): it calls
  `exitProcess` on a parse failure, which would take down the build process, and builds paths with
  `kotlin.io.path.Path`, which is `Path.of` underneath and does not exist below API 34.
- `ProfileDumper.kt` (`dumpProfile`): prints a binary profile back as text.

Nothing in the copied files refers to either. A re-sync should keep leaving them out.

## Re-syncing

From an AGP sources jar of the version to take (`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/
gradle/<version>/*/gradle-<version>-sources.jar`), from this directory:

    unzip -o <sources jar> 'com/android/tools/profgen/*'
    rm com/android/tools/profgen/ProfgenUtils.kt com/android/tools/profgen/ProfileDumper.kt

then update the version and checksum above and run `:android-support:test --tests '*ArtProfile*'`, which
builds a release APK and checks the packaged profile against the dex it was compiled for.
