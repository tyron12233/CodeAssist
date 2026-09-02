# Hello Plugin

A CodeAssist plugin shipped as its own Android app. The IDE finds it through the package manager, reads its
packaged manifest, and loads its classes off the installed APK. The full explanation of the model is in
[docs/writing-plugins.md](../../docs/writing-plugins.md); this is the smallest complete example of it.

It contributes to three surfaces, each a plain extension-point registration in
[`HelloPlugin.kt`](src/main/kotlin/com/example/hello/HelloPlugin.kt):

- a **command** in the command palette and the More menu (`UI_ACTION_EP`);
- a **category** in Settings (`SETTINGS_PAGE_EP`);
- **log lines** attributed to `com.example.hello`, which the Logs screen can filter by.

## What makes it a plugin

Three things, and nothing else:

| Part | Where |
| --- | --- |
| The packaged manifest, as TOML | [`res/raw/codeassist_plugin.toml`](src/main/res/raw/codeassist_plugin.toml) |
| A marker activity carrying `dev.ide.codeassist.action.PLUGIN` and pointing `meta-data` at that resource | [`AndroidManifest.xml`](src/main/AndroidManifest.xml) |
| The entry-point class the manifest names | [`HelloPlugin.kt`](src/main/kotlin/com/example/hello/HelloPlugin.kt) |

The activity is both the discovery marker and the app's own screen, so the plugin is a real app rather than a
bare code container.

## Build and install

```
./gradlew :samples:hello-plugin:installDebug
```

Then restart CodeAssist and open **Settings > Plugins > Installed**. The plugin loads at startup, so a
freshly installed or newly enabled plugin appears after the next launch. Its palette command is under
**Hello: say hello**, and its settings category appears once a project is open.

If it does not appear, the reason is on its row in that same screen. A row with a reason and no switch is a
plugin the IDE found but could not read.

## Building outside this repository

Two things change when this is a project of its own rather than a module of this build.

**The SPI arrives from Maven.** Here it is `compileOnly(project(":plugin-api"))`. Elsewhere, use the
published coordinates:

```kotlin
dependencies {
    compileOnly("io.github.tyron12233:plugin-api:1.0.0")
    compileOnly("io.github.tyron12233:platform-core:1.0.0")
}
```

The SPI carries its own version, independent of the IDE's: it changes far less often than the app ships, and
compatibility is decided by `apiVersion` and `minHostVersion` rather than by this coordinate. Before the
first Central release, publish them locally and add `mavenLocal()` to your repositories:

```
./gradlew :plugin-api:publishToMavenLocal :platform-core:publishToMavenLocal
```

`compileOnly` is the important part either way. The IDE's classloader is the parent of the plugin's, so the
SPI, the Kotlin stdlib and the Compose runtime resolve to the IDE's copies; bundling a second copy of any of
them achieves nothing, and bundling a mismatched version is how a plugin gets a linkage error reported
against it.

**The Kotlin version has to be pinned.** Inside this build the Kotlin Gradle plugin is already on the
classpath at the version the IDE was compiled with, so nothing extra is needed. On its own, an AGP 9 project
defaults to an older built-in Kotlin that cannot read the SPI's metadata, and the `kotlin-android` plugin is
rejected outright ("no longer required since AGP 9.0"). Set the compiler version instead:

```kotlin
@file:OptIn(
    org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
    org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
)

kotlin {
    compilerVersion.set("2.4.0")
}
```

with `kotlin.compiler.runViaBuildToolsApi=true` in `gradle.properties`. Without both, the build fails on
`Module was compiled with an incompatible version of Kotlin`.

## Things worth knowing before you copy this

- The manifest `id` is the plugin's identity, not a display name. It takes the same shape as an
  `applicationId` (letters, digits, `.`, `-`, `_`), and it is compared exactly wherever it appears, including
  another plugin's `dependsOn`.
- `apiVersion` must equal the IDE's `PLUGIN_API_VERSION`, and `minHostVersion` is checked against the running
  IDE. Both mismatches are reported on the plugin's row.
- `essential` and `trusted` are ignored whatever the file says: those are the IDE's to decide.
- The plugin runs in the IDE's process, under its UID and its granted permissions. Class loading isolates
  versions, not privileges.
- Your plugin's own license is yours to choose. The two SPI modules are GPL-3.0-or-later **with** the
  Classpath exception (see [LICENSE-EXCEPTION](../../LICENSE-EXCEPTION)), so linking against them does not
  make your plugin a derivative work of CodeAssist.
- Enabling or disabling a plugin takes effect on the IDE's next launch. The manager loads once at startup and
  does not hot-swap.
- A plugin that contributes only a settings page has no visible surface until a project is open, because
  plugin settings pages come from the project's engine. Add a palette command if you want something visible
  straight away.
