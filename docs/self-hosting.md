# Self-hosting

Building CodeAssist with CodeAssist's own build system. No Gradle, no AGP.

```bash
./gradlew generateNativeModel      # Gradle exports what it configured
codeassist import --project .      # the IDE reads it into a workspace
codeassist --task build:platform-core
```

The first command is the only one that needs Gradle, and only because Gradle is the only thing that can
evaluate this repository's build scripts. Everything after it is CodeAssist building CodeAssist.

## Why the model is exported rather than read

[`GradleProjectImporter`](../app/ide-core/src/main/kotlin/dev/ide/core/gradle/GradleProjectImporter.kt) reads
build scripts as text, which is all that is possible for a project a user merely opened. It cannot read this
one. `settings.gradle.kts` spreads `include(...)` over eighty lines, assigns every module's `projectDir` from
a map (`:platform-core` lives at `platform/platform-core`), and gates a whole block on an environment
variable. A reader that derives a module's directory from its Gradle path finds nothing here.

So Gradle exports instead. `generateNativeModel`
([`NativeModelExtractor`](../buildSrc/src/main/kotlin/dev/ide/build/nativemodel/NativeModelExtractor.kt))
walks the *configured* model and writes `.platform/gradle-model.json`: every module's directory, type, source
roots, and its declared dependencies with the versions the catalog resolved. Plugin-DSL dependency
accessors arrive as real coordinates, so `api(compose.material3)` exports as
`org.jetbrains.compose.material3:material3:1.9.0` with no special handling.

[`GradleModelImporter`](../app/ide-core/src/main/kotlin/dev/ide/core/gradle/GradleModelImporter.kt) reads that
back into an ordinary native workspace. It detects with a higher confidence than the script reader, so a
project carrying an export is imported from it. The result is a normal CodeAssist project: `.platform/` plus
a `module.toml` per module, which the IDE owns from then on.

This is not specific to this repository. Any Gradle project willing to run one task gets an import with the
fidelity of the real thing rather than of a text scrape.

### Targets

A Kotlin Multiplatform module has several compilations; a CodeAssist module has one. The export picks which:

```bash
./gradlew generateNativeModel                            # android (the APK's shape), the default
./gradlew generateNativeModel -PnativeModel.target=desktop
```

A module exported for `android` carries `commonMain` + `jvmShared` + `androidMain` and none of Skiko. The
source sets above the leaf platform one are marked common, which is what the Kotlin compiler needs in order
to accept an `expect` in one and its `actual` in another inside a single compilation
([`KotlinFacet`](../lang/lang-kotlin/src/main/kotlin/dev/ide/lang/kotlin/build/KotlinFacet.kt)).

Switching targets rewrites the model, and a module's *type* is not re-derived on a re-import. Delete
`.platform/workspace.json` and the `module.toml` files before switching.

## What had to change in the build system

Self-hosting found four things that were wrong for every project, not just this one.

| | Symptom | Fix |
|---|---|---|
| Test-only module edges | `testImplementation project(":test-support")` closed a cycle and the build refused to configure | `directModuleDeps` keeps only dependencies that reach the compile or runtime classpath |
| Test source sets | `src/test` compiled into the main output, where its assertion library is not on the classpath | `sourceRootDirs` skips source sets whose scope is test-only |
| Generated Kotlin | a module whose sources are *all* generated got no `compileKotlin` task, because it had no `.kt` when the graph was built | the task is registered whenever a Kotlin compiler is available, and is a no-op when there is nothing to compile |
| Shared `build/` | CodeAssist read Gradle's generated sources as its own and packaged Gradle's classes | a module can move its output (`ModifiableModule.outputRelPath`); the import puts CodeAssist's under `build/codeassist/` |

## What the build system gained

**Compose resources.** `:ide-ui-resources`, `:agent-ui` and `:vcs-ui` have no hand-written Kotlin at all:
their content is a `Res` class generated from `composeResources/`, which only the Compose Gradle plugin
produced. [`ComposeResourcesGenerator`](../build-system/jvm-build/src/main/kotlin/dev/ide/build/jvm/compose/ComposeResourcesGenerator.kt)
reproduces it: the typed accessors, and the `.cvr` files their byte ranges point into.

**A class-transform seam.** [`ClassTransform`](../build-system/build-api/src/main/kotlin/dev/ide/build/ClassTransform.kt)
(`platform.classTransform`) rewrites compiled classes between compilation and dexing, which is AGP's
`AsmClassVisitorFactory` and the only way to fix a dependency that cannot run as published. Instrumented
copies are written beside the originals and cached by content, so unchanged dependencies are rewritten once.
The IDE's own build needs thirteen such rewrites to make the bundled Kotlin compiler start on ART.

## Where it stands

Verified on the `desktop` target, where every module is a plain JVM one and the whole graph goes through the
native Java pipeline:

```bash
./gradlew generateNativeModel -PnativeModel.target=desktop
codeassist import --project .
codeassist --task build:platform-core    # a leaf: 253 classes
codeassist --task build:ide-ui-core      # multiplatform + Compose + generated resources: 188 classes
```

`:ide-ui-core` is the interesting one. It compiles `expect` declarations in `commonMain` against their
`actual`s in `desktopMain` as one compilation, applies the Compose compiler plugin, and depends on
`:ide-ui-resources`, whose entire content the build generated a moment earlier.

The `android` target exports the APK's shape, which types the multiplatform modules as `android-lib` and
routes them through the Android pipeline. That path is the next milestone, not this one.

### The boundary a deep build hits

Building further up the graph stops at `:kotlin-compiler-deps`. That module has **no sources**: its artifact
is assembled by a Gradle task (`MergeUnshadedCompiler`) out of twenty-five downloaded jars. The export says so
faithfully (an empty source-set list), the native build compiles nothing, and every module that imports
`KotlinCoreEnvironment` from it then fails to resolve it. `:art-compat` is the same shape, with a task that
relocates bytecode instead.

Those two are the smallest, sharpest statement of what is left: not a missing compiler feature, but build
logic that lives in a `build.gradle.kts` and has to be rewritten as a `BuildPlugin` before anything above
them compiles.

Not yet:

- **Modules whose artifact is assembled rather than compiled** (`:kotlin-compiler-deps`, `:art-compat`), and
  therefore everything downstream of them.
- **`:ide-android` assembles.** Its `build.gradle.kts` is 1088 lines of tasks that produce the APK's inputs
  (staged asset jars, a re-dexed R8, extracted SDK stubs), plus `:art-compat` and `:kotlin-compiler-deps`,
  which relocate and merge jars. The seams they need now exist; the tasks themselves have to be written as a
  build plugin.
- **A dependency declared non-transitively.** `compose-compiler-plugin-for-ide` publishes a POM naming
  eleven artifacts that do not exist; Gradle never sees them because the configuration is
  `isTransitive = false`, which the model has no way to say.
- **Tests.** `BuildGoal.TEST` has no task behind it, so the exported test source sets and dependencies are
  carried but unused.
- **On device.** A separate problem: the compile classpath alone is the unshaded Kotlin compiler plus the
  IntelliJ platform.
