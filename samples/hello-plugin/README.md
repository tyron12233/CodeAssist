# Hello Plugin

A CodeAssist plugin shipped as its own Android app. The IDE finds it through the package manager, reads its
packaged manifest, and loads its classes off the installed APK. The full explanation of the model is in
[docs/writing-plugins.md](../../docs/writing-plugins.md); this is the smallest complete example of it.

It has both facets a plugin can have. The **engine facet**,
[`HelloPlugin.kt`](src/main/kotlin/com/example/hello/HelloPlugin.kt), contributes these surfaces, each a plain
extension-point registration:

- a **command** in the command palette and the More menu (`UI_ACTION_EP`);
- an **editor action** at the caret (the same extension point, placed on `ActionPlaces.EDITOR`): it wraps
  the Kotlin call under the cursor in `runCatching { }` and leaves the result selected, and is listed in the
  Alt-Enter popup, the editor's overflow menu, and the palette while an editor is focused;
- a **category** in Settings (`SETTINGS_PAGE_EP`);
- **log lines** attributed to `com.example.hello`, which the Logs screen can filter by.

It also contributes **build logic**, in
[`HelloBuildPlugin.kt`](src/main/kotlin/com/example/hello/HelloBuildPlugin.kt). Three more registrations from
the same engine facet, since build contributions are extension points like any other:

- a **`BuildPlugin`** (`BUILD_PLUGIN_EP`) that registers `:<module>:helloBuildReport` on every build graph and
  hangs it off the module's `assemble` aggregate by name;
- a **`SourceGenerator`** (`SOURCE_GENERATOR_EP`) that emits `hello.buildinfo.HelloBuildInfo` ahead of
  compilation, for a module whose own sources reference it (an Android module, or a plain JVM module that
  declares a generated source root: the native pipeline registers no `generateSources` task without one);
- a **`RunTaskProvider`** (`RUN_TASK_PROVIDER_EP`) that puts "Hello: write build report" in the Run picker and
  executes it.

It also shows two things worth copying: the build plugin reads its own settings page through
`SETTINGS_ACCESS`, which is how a contribution consults a setting outside the page's own callbacks, and the
Run row builds a one-task `TaskGraph` by hand, which is less code than the engine's real graph for something
with no dependencies. [docs/custom-build-plugins.md](../../docs/custom-build-plugins.md) walks through all of
it.

The **UI facet**, [`HelloUiPlugin.kt`](src/main/kotlin/com/example/hello/HelloUiPlugin.kt), adds a **tool
window** on the editor's left rail with a real Compose body.

The two are separate classes because a `@Composable` body cannot live in the engine module, not because they
are separate programs. They are named by the same manifest and loaded off this one APK on the same
classloader, so [`HelloState`](src/main/kotlin/com/example/hello/HelloState.kt) is one object to both: the
palette command writes to it, the panel reads it, and nothing is serialised or routed in between.

## What makes it a plugin

Three things, and nothing else:

| Part | Where |
| --- | --- |
| The packaged manifest, as TOML | [`res/raw/codeassist_plugin.toml`](src/main/res/raw/codeassist_plugin.toml) |
| A marker activity carrying `dev.ide.codeassist.action.PLUGIN` and pointing `meta-data` at that resource | [`AndroidManifest.xml`](src/main/AndroidManifest.xml) |
| The entry-point class `entryPoints` names | [`HelloPlugin.kt`](src/main/kotlin/com/example/hello/HelloPlugin.kt) |
| The UI class `uiEntryPoints` names, if the plugin has UI | [`HelloUiPlugin.kt`](src/main/kotlin/com/example/hello/HelloUiPlugin.kt) |

The activity is both the discovery marker and the app's own screen, so the plugin is a real app rather than a
bare code container.

## Build and install

```
./gradlew :samples:hello-plugin:installDebug
```

Then restart CodeAssist and open **Settings > Plugins > Installed**. The plugin loads at startup, so a
freshly installed or newly enabled plugin appears after the next launch. Its palette command is under
**Hello: say hello**, its settings category appears once a project is open, and its tool window is on the
editor's left rail once one is. Build any project and the console's **Steps** tab lists
`:<module>:helloBuildReport`; the Run picker carries the row that writes the same report on its own.

If it does not appear, the reason is on its row in that same screen. A row with a reason and no switch is a
plugin the IDE found but could not read.

## Building outside this repository

Two things change when this is a project of its own rather than a module of this build.

**The SPI arrives from Maven.** Here it is `compileOnly(project(":plugin-api"))`. Elsewhere, use the
published coordinates:

```kotlin
dependencies {
    compileOnly("io.github.tyron12233:plugin-api:1.2.0")
    compileOnly("io.github.tyron12233:platform-core:1.2.0")

    // The build facet. It brings project-model-api, vfs-api and platform-core with it.
    compileOnly("io.github.tyron12233:build-api:1.2.0")

    // The UI facet, plus the Compose the IDE bundles. Pinned: your @Composable code composes into the
    // IDE's own Compose runtime, so a newer version here fails at first composition, not at build time.
    compileOnly("io.github.tyron12233:plugin-ui-api:1.2.0")
    compileOnly("androidx.compose.runtime:runtime:1.11.2")
    compileOnly("androidx.compose.foundation:foundation:1.11.2")
    compileOnly("androidx.compose.ui:ui:1.11.2")
    compileOnly("androidx.compose.material3:material3:1.4.0")
}
```

The Compose *compiler* needs no declaration when the project is built by CodeAssist: it applies the plugin to
any module whose classpath carries the Compose runtime. Building with Gradle, apply
`org.jetbrains.kotlin.plugin.compose` (this module does).

The SPI carries its own version, independent of the IDE's: it changes far less often than the app ships, and
compatibility is decided by `apiVersion` and `minHostVersion` rather than by this coordinate. Before the
first Central release, publish them locally and add `mavenLocal()` to your repositories:

```
./gradlew :plugin-api:publishToMavenLocal :platform-core:publishToMavenLocal \
    :plugin-ui-api:publishToMavenLocal :build-api:publishToMavenLocal
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
- Your plugin's own license is yours to choose. The published SPI modules are GPL-3.0-or-later **with** the
  Classpath exception (see [LICENSE-EXCEPTION](../../LICENSE-EXCEPTION)), so linking against them does not
  make your plugin a derivative work of CodeAssist.
- Enabling or disabling a plugin takes effect on the IDE's next launch. The manager loads once at startup and
  does not hot-swap.
- A plugin that contributes only a settings page has no visible surface until a project is open, because
  plugin settings pages come from the project's engine. Add a palette command if you want something visible
  straight away.
- An editor action's `visible` predicate runs on caret moves, for every registered action. Keep it to the
  flat `caret` snapshot, and do the real work in `perform`, which runs only for the action the user picked.
- `CaretContext.nodeText` is capped, so a long node arrives truncated. Read the exact source out of
  `documentText` using `nodeStart`/`nodeEnd`, as the editor action here does.
- An action that has to walk the syntax tree or resolve a symbol wants the analysis tier instead
  (`ActionProvider` in `analysis-api`), which is published too. Both tiers appear in the same popup.
- A UI facet gets a deliberately narrow `UiContext` (the active file, the project root, `openFile`,
  `openScreen`). Anything more belongs in the engine facet, which has the whole engine SPI and is a plain
  function call away. The IDE's internal UI model, with its `IdeBackend` handle, is not published.
- A plugin has no `Context` for its own package: no drawables, no `stringResource`. Icons are ids in the
  IDE's registry (`iconId = "sparkle"`) and text is Kotlin string literals.
- Your plugin's code runs in the IDE's process, which is **minSdk 26**, so the JDK method floor applies to it
  too. `Path.of`, `Files.readString`, `Files.writeString` and `Stream.toList()` do not exist on the older
  devices the IDE supports; use `Paths.get`, `Files.readAllBytes`, `Files.write` and `Collectors.toList()`.
  The call dexes cleanly either way and throws only when the line is reached.
- Build contributions have their own capabilities (`build.task`, `build.sourceGenerator`,
  `build.runTask`), so the consent gate names them like any other. Declare the ones you actually register:
  the editor flags a capability no facet can deliver, and an unknown one as a typo.
