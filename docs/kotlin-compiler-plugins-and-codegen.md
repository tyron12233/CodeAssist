# Kotlin compiler plugins and source generation (Room, KSP, …)

Design for two related capabilities the editor and build do not yet have in a general form:

1. **Arbitrary Kotlin compiler plugins** (Compose-style bytecode transformers, kotlinx-serialization,
   Parcelize, all-open/no-arg).
2. **Source-generating tools** (Room, Dagger/Hilt, Moshi, ViewBinding/DataBinding) that produce new
   `.kt`/`.java` the rest of the module then compiles against.

These look like one feature but are two. A compiler plugin runs inside kotlinc and rewrites bytecode; it
emits no files. A source generator emits files that must be compiled, indexed, and made visible to
completion. **KSP is the bridge**: it is delivered as a compiler-frontend embedding, and it is how Room and
friends generate code in Kotlin projects. So the generalized compiler-plugin path (Part 1) is the
foundation, and source generation (Parts 2 and 3) is a layer on top of it.

The crux for both, on ART, is the same: a device cannot load arbitrary JVM bytecode, so plugin and
processor classes must be dexed and reachable from a classloader. The decision taken here is **runtime
dexing** (D8 the plugin/processor classpath on device, load through a `DexClassLoader`) rather than only
shipping a curated bundled set. For the editor, the decision is a **hybrid**: synthesize the deterministic
generators cheaply, and run the real processor in the background for the rest.

## Where things stand

**The compiler-plugin seam exists but is hardcoded to Compose.**

- `KotlinJvmCompiler.compile(…, compilerPlugins: List<Path>, pluginOptions: List<String>)` and
  `IncrementalKotlinCompiler.compile(… same params …)` already map straight onto kotlinc's
  `pluginClasspaths` (`-Xplugin`) and `pluginOptions` (`-P plugin:<id>:<k>=<v>`).
  `IncrementalKotlinCompiler` folds both into its context hash, so changing a plugin invalidates the
  incremental baseline. This plumbing is generic and stays.
- The *application* is bespoke. `KotlinCompileTask.execute()` (lang-kotlin `build/`) calls
  `ComposeCompilerPlugin.isComposeModule(classpath + bootClasspath)` and, if true, appends one jar. It
  passes **no `pluginOptions` at all**, so even Compose's own options (strong skipping, stability config,
  compiler metrics/reports) are unreachable. There is no registry for a second plugin.
- ART trick (`ComposeCompilerPlugin` kdoc): the registrar class is dexed into `:ide-android`
  (`kotlin.compose.compiler.plugin`, non-transitive), and the bundled jar serves only its
  `META-INF/services` descriptor. kotlinc's `ServiceLoaderLite` reads the class *name* from the jar and
  resolves the *class* by parent delegation to the dexed app classloader.

**Source generation does not exist.** `AnnotationProcessor` in `language-api` is a stub; every `processors`
impl returns `emptyList()`. What exists is `SYNTHETIC_CLASS_EP` (`SyntheticClassProvider`), which fakes
known generated classes (`R`, `BuildConfig`, Kotlin facades) by rendering them to Java source into the JDT
name-environment overlay plus the Kotlin symbol service plus the index. That is "pretend the output exists
for completion," not "run the generator." Its kdoc already names Room/Dagger/ViewBinding as intended
future stand-ins.

**The generated-source build plumbing is mostly already in place.**

- `ContentRole.GENERATED` exists; `sourceFiles()` / `kotlinSourceFiles()` treat GENERATED roots exactly
  like SOURCE roots.
- `TaskGraphImpl.inferOutputDeps()` auto-wires an edge whenever one task's output dir is a prefix of
  another's declared input path. That is already how aapt2Link's R.java feeds compileJava with no explicit
  `dependsOn`. A generator task emitting into a dir the compile tasks read slots in for free.
- The index scans GENERATED roots (`IndexScope.sourceRoots`).
- Gaps: there is no file watcher (`LocalFileSystem.watch` is `NoopWatch`), so files written to disk by a
  generator are invisible to the editor until something calls `reindexSource` / `invalidateSyntheticClasses`.
  The build graph is built once from the model, so roots are not injected dynamically except Android's
  hardcoded `genJava`.

## Part 1: a generalized compiler-plugin seam

### The extension point

Replace the bespoke `ComposeCompilerPlugin` object with an EP so plugins are a registration, not a host
edit (the same shape as every other `platform.*` EP):

```kotlin
/** Contributes a Kotlin compiler plugin (registered through KOTLIN_COMPILER_PLUGIN_EP). */
interface KotlinCompilerPlugin {
    val pluginId: String                               // "-P plugin:<pluginId>:…" prefix

    /** True when this module should compile with the plugin (Compose: Composable.class on the classpath). */
    fun appliesTo(module: Module, classpath: List<Path>): Boolean

    /** The plugin classpath to feed kotlinc. May be several jars (e.g. KSP's runner + its deps). */
    fun classpath(module: Module): List<Path>

    /** `-P` options, already in `<id>:<key>=<value>` form. Empty for a plugin with no config. */
    fun options(module: Module): List<String> = emptyList()

    /**
     * Classes that must be loadable at runtime to instantiate the plugin's registrar. On ART these are
     * D8-dexed and loaded via DexClassLoader (see "ART class loading"). On desktop the jars load directly.
     */
    fun registrarClasspath(module: Module): List<Path> = classpath(module)
}

val KOTLIN_COMPILER_PLUGIN_EP = ExtensionPoint<KotlinCompilerPlugin>("platform.kotlinCompilerPlugin")
```

`KotlinCompileTask.execute()` and `AndroidKotlinCompileTask` then iterate the EP instead of naming Compose:

```kotlin
val plugins  = platform.extensions(KOTLIN_COMPILER_PLUGIN_EP).filter { it.appliesTo(module, classpath) }
val jars     = plugins.flatMap { it.classpath(module) }
val options  = plugins.flatMap { it.options(module) }
compiler.compile(kt, javaSources, classpath, out, level, bootClasspath, compilerPlugins = jars, pluginOptions = options)
```

Compose becomes the reference impl of this interface (its `isComposeModule` is `appliesTo`, `jar()` is
`classpath`). This also fixes the dropped-`pluginOptions` bug as a side effect.

### ART class loading (shared by plugins and processors)

This is the load-bearing piece and is reused verbatim by KSP in Part 2.

kotlinc's CLI plugin path builds a `URLClassLoader` over `pluginClasspaths` and uses `ServiceLoaderLite`
to find the registrar. On ART a `URLClassLoader` can read a jar's *resources* (the service descriptor) but
cannot *define classes* from `.class` bytes; only dex can. Today Compose survives only because its
registrar is pre-dexed into the app and resolves by parent delegation.

To support plugins that are **not** bundled at build time, dex them on device. The IDE already runs D8
in-process (`D8InProcessDexer`):

1. Resolve the plugin classpath (Maven, the same resolver used for deps).
2. Content-hash the classpath, and D8 it to a dex under `.platform/caches/kotlinc-plugins/<hash>/` (cached;
   re-dex only on change). This mirrors the existing per-jar dex cache used by the Android dexer.
3. Build a `DexClassLoader` over that dex, parented to the app classloader (so it shares the already-dexed
   Kotlin compiler classes).
4. Make kotlinc resolve the registrar through that loader. Two options:
   - **Programmatic registration (preferred).** Drop below `K2JVMCompiler().exec(…)`, build the
     `KotlinCoreEnvironment`/`CompilerConfiguration` directly, instantiate the
     `CompilerPluginRegistrar` from the `DexClassLoader`, and register it on the configuration. This avoids
     the "URLClassLoader cannot define classes" problem entirely; the jar is then needed only on desktop.
   - **Parent-classloader injection.** Keep the CLI `-Xplugin` path but supply the `DexClassLoader` as the
     parent so `ServiceLoaderLite`'s class resolution delegates to dex. Lighter change, but still relies on
     the jar for the service descriptor.

   Build the EP so callers do not care which path a plugin uses; `registrarClasspath` is what gets dexed.

Desktop keeps the current direct-jar path (no dexing). The dexing branch is gated on the host being ART.

A registry of blessed plugins (verified version, known-good options) is still worth keeping for UX and
safety, but it is a curation layer over the same runtime-dex mechanism, not a separate code path.

## Part 2: source generation in the build, KSP-first

**Commit to KSP, not KAPT.** KAPT runs javac annotation processing via stub generation: it needs the
javac APT pipeline, is slow, and is awkward on ART. KSP runs against Kotlin/Java declarations directly and
emits source, and Room, Moshi, Dagger/Hilt, and most modern processors ship KSP processors. One mechanism,
the whole ecosystem.

Use **KSP2's standalone programmatic entry** (`KotlinSymbolProcessing(KSPConfig, providers, logger)`)
rather than KSP1's `-Xplugin` extension. KSP2 runs its own frontend as a plain library call, the same way
`KotlinJvmCompiler` calls `K2JVMCompiler`, which keeps the codegen round cleanly separate from the final
compile round and avoids coupling to compiler-plugin internals. The processors' `SymbolProcessorProvider`
classes are the thing that must be loadable, so they go through Part 1's runtime-dex path. **KSP is the
first real "arbitrary third-party code on ART" customer**, which is why Part 1's dexing path is built with
it in mind.

### The build task

Add a per-module `kspKotlin` task (a new `KspTask` in lang-kotlin `build/`, or its own small module):

- **Inputs:** the module's `.kt`/`.java`, the compile classpath, and the resolved processor classpath
  (content-hashed so a processor-version bump invalidates).
- **Action:** load processors via the runtime `DexClassLoader`, run KSP2 over the module, write generated
  sources to `<module>/build/generated/ksp/<variant>/{kotlin,java}` and resources to `…/resources`.
- **Outputs:** those dirs.
- **Wiring:** register the generated dirs as `ContentRole.GENERATED` content roots on the module (or
  append them to the compile tasks the way Android appends `genJava`). `compileKotlin` and `compileJava`
  already enumerate GENERATED roots, and `inferOutputDeps()` already creates
  `kspKotlin → compileKotlin → compileJava` edges from the output/input path overlap. No new graph code.

```
        ┌─ kspKotlin (run processors → generated/ksp/…) ─┐
sources ┤                                                 ├─ compileKotlin → compileJava → jar/dex
        └─────────────── (generated roots) ──────────────┘
```

### Incrementality

Lean on KSP's own incremental model (`Dependencies`, aggregating/isolating outputs) rather than teaching
`KotlinAbi` about cross-file generation. KSP tracks which inputs a generated file depends on and re-runs
only the affected processors; a changed `@Entity` invalidating its generated DAO is KSP's responsibility.
Above that, the task graph's content fingerprints already re-run `compileKotlin` only when the generated
dir actually changes.

## Part 3: editor visibility, hybrid

Running Room's full processor on every keystroke is a non-starter, so the editor uses three tiers.

**Tier 1: synthesize the deterministic generators** as `SyntheticClassProvider`s (reuse `SYNTHETIC_CLASS_EP`,
already wired into the JDT overlay, the Kotlin symbol service, and the index). No processor runs.

- **ViewBinding and DataBinding** are ideal: the output is a pure function of the layout XML, which is
  already parsed (lang-xml). A `ViewBindingSyntheticProvider` emits `<Layout>Binding` with its typed view
  fields and `inflate`/`bind` methods directly from the parsed layout. Instant, zero-cost completion.
- `R` and `BuildConfig` already work this way.

**Tier 2: run real KSP in the background** for the non-deterministic generators (Room, Dagger), debounced
and coalesced (reuse the engine's background lane / `EngineScheduler`). On completion, write into the
GENERATED root and fire the invalidation signal below.

**Tier 3: always run real KSP at build time** so the APK is correct regardless of what the editor faked.

### The one new piece of plumbing: a "generated sources changed" signal

Because there is no file watcher (`NoopWatch`), generated files landing on disk are invisible until the
editor is told. Background codegen (Tier 2) and the build (Tier 3) must explicitly push:

- `indexService.reindexSource(<generated root>)` so go-to-symbol and unimported-class completion see the
  new types, and
- `invalidateSyntheticClasses()` so the overlay drops the Tier-1 synthetic stand-in once the real generated
  source exists (the open-document/overlay merge already lets a real type override a synthetic of the same
  FQCN).

This is the single genuinely new editor hook. Everything else reuses existing seams.

## ART class-loading model, summarized

| Consumer | Desktop | ART (device) |
|---|---|---|
| Compose plugin (today) | jar on `pluginClasspaths` | registrar pre-dexed into `:ide-android`, jar for descriptor |
| Arbitrary compiler plugin | jar on `pluginClasspaths` | D8-dex classpath → `DexClassLoader` → programmatic registration |
| KSP processors (Room, …) | jars on processor classpath | same D8-dex → `DexClassLoader` path |

One dexing mechanism (D8 in-process, content-hash cached under `.platform/caches/`), one loader strategy,
shared by both plugins and processors.

## Roadmap

1. **Generalize the seam.** [DONE] `KOTLIN_COMPILER_PLUGIN_EP` + `KotlinCompilerPlugin` in lang-kotlin;
   Compose is the reference impl; `pluginOptions` wired end to end; the plugin list is threaded
   `IdeServices` -> build systems -> compile tasks. Compose behaves exactly as before.
2. **Runtime-dex plugin loading.** Engine [DONE], device wiring staged.
   - Proven on desktop: `KotlinJvmCompiler` has a programmatic-registration path
     (`compileViaRegistrars`) selected by `runtimePluginClasspaths`, loading each plugin's
     `CompilerPluginRegistrar` through a `KotlinPluginLoader` and registering it on a
     `CompilerConfiguration.create()`-built config (which `createForProduction` then processes). The
     `DefaultKotlinPluginLoader` (`URLClassLoader`) and a real Compose load are verified by
     `ProgrammaticPluginRegistrationTest`. The path is threaded through `IncrementalKotlinCompiler` and
     both compile tasks (dormant until a plugin declares `KotlinPluginLoading.RUNTIME_REGISTRAR`).
   - Key fact: the manual bootstrap must build the config via `CompilerConfiguration.create()` (it calls
     `registerExtensionStorage()`); a bare `CompilerConfiguration()` silently skips plugin processing. Also
     `CompilerPluginRegistrar.registerExtensions` is a member-extension on `ExtensionStorage` (can't be
     `::`-referenced).
   - The ART `KotlinPluginLoader` is implemented + injected (compile-verified, device-run pending):
     `ArtKotlinPluginLoader` in `:ide-android` D8-dexes the plugin classpath (content-hash cached under
     `cacheDir/kotlinc-plugins`), packages the `classes*.dex` into a jar, and loads it via `DexClassLoader`
     parented to the app classloader. It is threaded `AndroidIde.bootstrap` -> `ProjectManager.onDevice` ->
     `IdeServices` -> the `KOTLIN_JVM_COMPILER` service (desktop stays `DefaultKotlinPluginLoader`).
   - Remaining (device, needs an emulator/device): register a real `RUNTIME_REGISTRAR` plugin (e.g.
     kotlinx-serialization or Parcelize) and run it on device. Caveats to verify there: D8 dexes the plugin
     jar without a desugaring classpath (its references to the compiler classes resolve at runtime via the
     parent app classloader, but D8 may warn), and `-P` option processing on the registrar path is not wired
     yet (no current plugin uses options there).
3. **Source generation.** Seam [DONE], KSP2 runner pending.
   - The generic build seam is built + verified: `SourceGenerator` SPI + `SOURCE_GENERATOR_EP` (build-api),
     `GenerateSourcesTask` (build-engine), wired in `JavaPlugin`/`JavaBuildSystem` (and resolved from the EP
     in `IdeServices`, dormant until a generator is contributed). A generator emits into the module's
     `ContentRole.GENERATED` root; the existing `compileKotlin`/`compileJava` compile it with NO compile-task
     change (they already read GENERATED roots), and `JavaPlugin` adds an explicit `compile -> generateSources`
     edge (the gen dir is empty at graph-build time, so output/input inference alone wouldn't catch it — same
     as Android's aapt2 R.java). `SourceGenerationTest` proves it end to end (stub generator -> compiled +
     used by hand-written code -> runs; unchanged rebuild up-to-date, generator runs once).
   - Remaining: a KSP2 `SourceGenerator` impl, thread `generators` through `AndroidBuildSystem` too (this
     increment wired only `JavaBuildSystem`), prove with Room on desktop, then on device. KSP2-on-ART is
     device-bound like K2 (see `docs/kotlin-compiler-on-art.md`).
   - **KSP2 API recon (KSP 2.3.9, the latest):** standalone entry is
     `com.google.devtools.ksp.impl.KotlinSymbolProcessing(config: KSPConfig, providers: List<SymbolProcessorProvider>, logger: KSPLogger).execute(): ExitCode`.
     The runner is `symbol-processing-aa-embeddable` (~84 MB; bundles a relocated Kotlin Analysis API, so it
     is decoupled from the host kotlinc — runs its own frontend). The SPI (`SymbolProcessor`,
     `SymbolProcessorProvider`, `KSPLogger`) is in `symbol-processing-api`, but `KSPConfig`/`KSPJvmConfig` are
     NOT there in 2.3.9 (likely in `symbol-processing-common-deps`/`-cmdline` — confirm before wiring).
     Processors load via step 2's `KotlinPluginLoader` (URLClassLoader desktop / DexClassLoader ART).
   - **Blocker/risk:** no KSP build matches Kotlin 2.4.0 (KSP's new independent versioning tops out at 2.3.9);
     2.3.9-on-2.4.0 is unverified. A focused desktop spike (resolve the 3 KSP artifacts + a trivial
     `SymbolProcessor` + one `KotlinSymbolProcessing().execute()`) should confirm compatibility BEFORE adding
     KSP/Room as real deps or wiring the production generator.
4. **Editor hybrid.** ViewBinding/DataBinding synthetic providers (Tier 1 quick win); then
   background-KSP-with-invalidation (Tier 2) plus the reindex/overlay-invalidate signal.

## Open questions and risks

- **KSP2 API stability and ART fit.** KSP2's standalone entry is younger than KSP1; verify it runs on ART
  (analogous to the K2-on-ART spike in `docs/kotlin-compiler-on-art.md`) before committing step 3.
- **Programmatic registrar vs CLI path.** Dropping below `K2JVMCompiler().exec` means owning more of the
  compiler bootstrap (`KotlinCoreEnvironment` setup, configuration). Prototype it in isolation; fall back to
  parent-classloader injection if the bootstrap proves brittle across compiler versions.
- **Processor dependency trees.** Room's processor pulls a large transitive closure; dexing it on device is
  heavier than dexing one Compose jar. Lean on the content-hash cache and dex once per processor-version.
- **Multi-round resolution.** Generated code can reference other generated code. KSP handles its own
  multi-round generation internally; confirm the single `kspKotlin → compile` edge is sufficient and no
  generator needs to see another generator's output through a second build round.
