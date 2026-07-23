# Project: On-device Android/Java IDE framework

Context file for Claude Code. This repo is the **`*-api` interface scaffold + design docs** for an
extensible IDE framework that runs on-device (Android/ART) and edits & builds Android/Java projects
**without hosting Gradle**. Implementations (`*-impl`) are not written yet.

Language: **Kotlin** for the framework and SPI; **Java** for compiler-backend adapters (Eclipse JDT,
javac). Target language for the IDE's *users* is Java first.

## Read these first
- `docs/architecture-core-foundation.md` — full architecture: project model, two-level graph
  (composite project graph + intra-project module graph), build-system abstraction + Gradle
  compatibility layer, extension-point contracts, concurrency model, roadmap.
- `docs/completion-and-incremental-ast.md` — code-completion design: error-tolerant AST, the
  completion-token (dummy-identifier) technique, scope/binding resolution, incremental reparse.
- `docs/plugin-system.md` — the unified plugin model: the `Plugin` SPI (`plugin-api`) + `PluginManager`
  (`plugin-impl`), how the IDE's own built-ins (`ide-core/BuiltInPlugins.kt`) dogfood it in place of imperative
  host wiring, the extension-point + scoped-service substrate, `FILE_TYPE_EP` routing, and platform ports as
  application-scoped host services (`ide-core/PlatformPorts.kt`).

## Module map (dependencies point downward only — acyclic)
```
platform-core            no domain knowledge; depended on by all
  └─ vfs-api
       └─ project-model-api
            ├─ build-api
            └─ language-api
deps-api → project-model-api ; deps-impl → deps-api (Maven resolver: POM transitives + BOM platforms)
index-api → language-api ; index-impl → index-api ; lang-jdt → index-api (members index + index-backed completion)
lang-xml → { language-api, index-api } (XML LanguageBackend: tolerant parser + completion; Android-agnostic)
kotlin-compiler-deps = the ONE unshaded Kotlin compiler (`-for-ide` split incl. cli/K2JVMCompiler) + IntelliJ platform jars + support libs (no kotlin-compiler-embeddable anywhere)
lang-kotlin → { language-api, index-api, kotlin-compiler-deps } + kotlin-metadata-jvm + ow2-asm (editor-only Kotlin LanguageBackend: PSI parse → own symbols/inference/completion; registered in ide-core)
android-sdk-metadata → android-support + ow2-asm (build-time generator: attrs.xml + android.jar → metadata asset)
analysis-api → language-api, index-api (diagnostics/analyzers/quick-fixes; compiler unified as a diagnostic provider) ; analysis-impl → analysis-api (the engine)
block-api → language-api ; block-impl → block-api (projectional/block editor: DOM→BlockTree projection + surgical BlockEdit→DocumentEdit)
android-support → { build-api, build-engine, project-model-impl } (the Android plugin: facet, module types, variants, native APK pipeline)

ide-ui (Compose Multiplatform UI, commonMain — no project deps)
ide-core (IdeServices + IdeServicesBackend, the shared engine→UI bridge) → { ide-ui, the impls }
ide-desktop (JVM launcher) → ide-core ; ide-android (Android launcher) → ide-core
```
- `platform-core` (`dev.ide.platform`): ExtensionPoint/ExtensionRegistry, MessageBus, ActivityManager/
  ActivityScope (read/write-lock discipline), ProgressReporter, Disposable, ContentHash, PluginId.
- `vfs-api` (`dev.ide.vfs`): VirtualFile, VirtualFileSystem, VfsEvent (sealed), VfsListener.
- `project-model-api` (`dev.ide.model`, `dev.ide.model.graph`): Workspace/Project/Module/SourceSet/
  ContentRoot, OrderEntry (Library/Module/Sdk/`PlatformDependency` = a BOM) + DependencyScope (+ `exported`
  = api semantics; + an optional `variant` config qualifier = the `debugImplementation`/flavor semantics, null
  = shared), ClasspathSnapshot,
  LibraryTable/SdkTable, ModuleType, Variant, Facet, ProjectModelTransaction, ProjectGraph/ModuleGraph,
  CoordinationPolicy. **Build-variant filtering:** `Module.classpath(scope, transitive, variant)` takes an
  active config-name set (`{main, free, debug, freeDebug}`); null = include everything (back-compat), a
  non-null set drops any entry whose `variant` isn't in it (shared entries always stay) — direct + the module-
  dependency closure. `Variant.configurations` exposes that set generically (android-support's `AndroidVariant`
  fills it from the unfiltered candidate source-set names), so `project-model-impl` filters without knowing
  Android. Persisted as nested `[dependencies.<config>]` tables in `module.toml` (bare `[dependencies]` = shared,
  byte-identical to the pre-variant format). Defines Coordinate, BuildSystemId, LanguageLevel. Also `FileIconProvider`/`IconTarget`
  + the `platform.fileIcon` EP (pluggable project-tree icons → string icon ids; resolver `FileIconRegistry`
  + built-in `DefaultFileIconProvider` in project-model-impl; `AndroidFileIconProvider` in android-support).
- `build-api` (`dev.ide.build`): BuildSystem SPI; generic incremental Task/TaskInputs/TaskOutputs/
  TaskGraph/TaskExecutor engine (Gradle-mimic: model → task DAG → fingerprint up-to-date checks → cache).
- `build-engine` (`dev.ide.build.engine`): impl of build-api's engine — live-content fingerprints,
  persistent per-task `BuildCache`, `TaskGraphImpl` (Kahn levels), `TaskExecutorImpl` (up-to-date skip +
  bounded-parallel + cancel + per-task status events), and the native `JavaBuildSystem`: `compileJava → jar`
  (`createBuildGraph`) plus `createInterpretRunGraph` whose `InterpretExecTask` runs a console app's main by
  INTERPRETING its compiled bytecode on the `:jvm-interp` bytecode VM (`AlwaysRun`, the Gradle
  `application`-plugin `run` equivalent), over a `JavaCompile` port. **Kotlin/Java mixed modules:** an injected
  `KotlinCompile` port (mirror of `JavaCompile`, impl in lang-kotlin) drives a `compileKotlin` task
  (`KotlinBuild.kt`) registered *ahead of* `compileJava` for any module with `.kt`. Kotlin emits to a
  `kotlin-classes` sibling dir; that dir joins the Java compile classpath (Java → Kotlin), the `jar`/`classes`
  lifecycle (now multi-dir), and the run runtime classpath, while kotlinc is fed the module's `.java` for
  resolution (Kotlin → Java). Standard ordering (Kotlin first); a Java edit re-runs `compileKotlin`;
  incrementality holds. **Console runs use the bytecode interpreter, ONE path for desktop AND device** (the
  old `java`-fork `JavaExecTask` and the on-device dex-run `createDexRunGraph`/`JavaDexTask`/`DexExecTask` +
  `RunDexBackend`/`RunDexer`/`DexRunner`/`DexClassLoaderRunner`/`ForkedDalvikRunner` are all GONE): the run
  task hands the runtime classpath (class dirs + library jars, no dexing) to an injected `ProgramInterpreter`
  (`ProgramRun.kt`; impl `VmProgramInterpreter` in jvm-build, so build-engine stays VM-free). `VmProgramInterpreter`
  builds a FRESH `Vm` per run (statics reset), interprets the user's + libraries' classes from the classpath
  and bridges only the platform/stdlib namespaces (`java/`,`kotlin/`,`android/`,… → the real runtime), so
  nothing downloaded or user-built is loaded by the host class loader or dexed — the Play dynamic-code answer.
  Runs are **cancellable even mid-compute** (the VM loop checks a cancel flag every ~1024 instructions; the run
  thread is also interrupted to break a blocked stdin read). The **run sandbox now lives at the VM bridge**
  (`RunBridge`), not bytecode instrumentation: `System.exit`/`Runtime.exit`/`halt` → `ControlledExit` (ends the
  run not the IDE — an in-process `SecurityManager` is unsupported on ART), and network/file/reflection/process
  calls consult the injected `PermissionBroker` via `Guards.enforce` (blocks + prompts on an undecided category,
  `SecurityException` on deny). Best-effort over a curated API set, not a hardened sandbox. stdio: the
  interpreter redirects the process-global `System.out`/`err`/`in` to the run's `ProgramIo` for the run's
  duration (so interpreted output AND bridged stdlib I/O — Kotlin's `readln` — both reach the run console).
  On device the host injects `VmProgramInterpreter(peerFactory = DexPeerFactory())`, which dexes the small
  generated peer classes the VM needs when an interpreted object is handed to real platform code (a
  `Comparator`, a `Runnable`). **Threading:** the VM is multi-threaded — a `Thread` an interpreted program
  starts is a REAL host thread whose `run` re-enters the interpreter, so threads run in genuine parallel; each
  thread keeps its frame stack thread-local, `synchronized`/`wait`/`notify` on interpreted objects use real
  per-object `VmMonitor`s (ReentrantLock + Condition), class init follows JLS 12.4.2, and the shared class /
  resolution / peer state is concurrent. `java.util.concurrent` (atomics, executors, locks, `ThreadLocal`) is
  bridged to the real runtime, so it works unchanged. The run ends when `main` AND every non-daemon thread it
  started have finished (JVM exit semantics); Stop interrupts the whole `ThreadGroup`. It is a best-effort
  concurrency model, not a hardened JMM (raw non-`volatile` field visibility is relaxed). **Tradeoffs:**
  compute-heavy programs run much slower than native, and an unsupported `invokedynamic` bootstrap fails with
  `VmUnsupportedException`. JDT compile backend (`JdtBatchCompiler`/`JdtSourceCompiler`, ecj batch → `.class`) lives in lang-jdt.
  Wired into the IDE: the top-bar Run button + live `BuildConsole` (status pill, step graph, streamed log)
  via `IdeBackend.buildState`/`runBuild`/`stopBuild`. Roadmap steps 4–5 done.
- `android-support` (`dev.ide.android.support` + `.tools/.tasks`): the Android plugin (roadmap step 7).
  Pure kotlin/jvm — never links android.jar; invokes SDK tools at runtime behind injected ports.
  `AndroidFacet` (+`BuildType`/`ProductFlavor`) + `AndroidFacetCodec` (`[android]` TOML table; ints as
  `Long`, build types/flavors as inline-table arrays). `BuildType` also carries the R8/ProGuard config
  (`minifyEnabled`, `shrinkResources`, `proguardFiles`, `consumerProguardFiles`, inline `proguardRules`);
  `AndroidFacet` carries `r8FullMode` (default on) + `coreLibraryDesugaringEnabled` (codec emits these only
  when non-default). `AndroidAppModuleType`/`AndroidLibModuleType`;
  `AndroidVariants` (build-type×flavor cross-product → active source sets, Gradle naming). `AndroidBuildSystem`
  wires the incremental DAG `mergeResources → aapt2Compile → aapt2Link(+R,+extra-pkg R) → [compileKotlin →]
  compileJava(per module) → dexBuilder → {mergeProjectDex, mergeLibDex, mergeExtDex} → packageApk → sign` over build-engine
  — faithful to AGP: ONE `dexBuilder` task (`DexArchiveBuilderTask`) archives the three scopes (project /
  sub-module / external) into per-class dex archives (`Dexer.dexArchive`, D8 `DexFilePerClassFile`). The
  project is **per-class-file incremental** (a `.classmanifest` of content hashes → only changed classes
  re-dex, with the unchanged ones as D8's desugaring classpath); sub-module/external scopes are per-jar
  **content-hash** buckets (an unchanged lib is reused, not re-dexed). The scope merges (`DexMergeTask` under
  AGP's `mergeProjectDex`/`mergeLibDex`/`mergeExtDex` names) run only when their scope changed. minSdk ≥ 21 →
  native multidex (scopes stay split, packaged separately); minSdk < 21 → one `mergeDex` (MERGE_ALL).
  `mergeExtDex` is threshold-gated (`extMergeThreshold`, AGP's LIBRARIES_MERGING_THRESHOLD 50/500): per-lib
  below it, merge-all above. `packageApk` renumbers the dex layers into one `classes*.dex` set. The dexer
  suppresses benign D8 desugaring warnings (e.g. guava's MethodHandle helpers below min-api 26).
  **Packaging native libs + Java resources (AGP-faithful, `tasks/PackagingMerge.kt`):** two merge tasks feed
  `packageApk`/`BundleTask`, mirroring AGP's `merge<Variant>NativeLibs`/`merge<Variant>JavaResource`.
  `MergeNativeLibsTask` collects every `.so` (module `src/<set>/jniLibs` + dep-lib jniLibs + exploded-AAR `jni`
  + `.so` under `lib` inside dep jars) into one `<abi>`-laid-out dir the packager maps under `lib`;
  `MergeJavaResourcesTask` collects Java resources (module `src/<set>/resources` + non-class entries of
  sub-module/external jars, `.class` skipped) into `merged-java-res.jar` copied to the APK root (bundle: under
  `root/`). Both apply the facet's `packaging { }` (`AndroidPackaging`/`ResourcePackaging`/`JniLibsPackaging`,
  new `[android.packaging]` inline table in the codec) **layered over AGP defaults** (`PackagingRules`: exclude
  signatures/`MANIFEST.MF`/maven/`.kotlin_module`/… ; **merge** `META-INF/services/**`). Precedence exclude →
  merge → pickFirst → first-wins-with-warning (AGP errors on the last; we're lenient); sources offered
  project-first. `.so` packaged as-is (no NDK strip on device). New `ContentRole.JNI_LIBS`; `android-app`/
  `-lib` source sets gained `src/<set>/{resources,jniLibs}` roots. **Editable in the IDE** via the Module
  Config **Packaging** tab (`ModulesTab.Packaging`/`PackagingPane` → `ModuleService.get/updatePackagingOptions`
  → `facet.packaging`; AGP defaults shown read-only). The block is omitted from the `[android]` codec map when
  default so it lives on its own tab (not the generic Settings fields), and `updateModuleConfig` overlays UI
  values on the existing encoded facet so a Settings save can't clobber it. Verified `AndroidPackagingBuildTest`
  (SDK) + `PackagingMergeTest` (unit) + `ModuleConfigTest` (config UI).
  **Minify/release path:** `minifyEnabled` swaps the dexBuilder→merge chain for one `R8MinifyTask`
  (`minify<Variant>WithR8`) that shrinks+optimizes+obfuscates+dexes in a single R8 pass. Keep rules are
  gathered AGP-style (`ProguardConfig.kt`): aapt2 `--proguard` manifest/layout rules + `proguardFiles`
  (bundled defaults under `resources/proguard/` resolved like `getDefaultProguardFile(...)`, plus module
  files) + dep-lib/AAR `consumerProguardFiles` + inline `proguardRules`; `r8FullMode` picks full vs
  ProGuard-compat; `mapping.txt` → `outputs/mapping/<variant>/`. `shrinkResources` links proto (aapt2
  `--proto-format`), R8 shrinks resources in-process (`AndroidResourceProvider`/`Consumer`), then
  `ConvertResourcesTask` converts back to binary. `coreLibraryDesugaringEnabled` drives D8(debug)/R8(release)
  `java.*` rewriting + an `L8DexTask` that dexes the kept-whole `desugar_jdk_libs` runtime (injected
  `DesugarLib` artifacts; no-op when the host ships none). The `Shrinker` port takes a `ShrinkRequest`; the
  desugar config folds into the dex cache key only when enabled (a no-desugaring build's cache is unchanged).
  Verified end-to-end through real R8/D8/L8 (`AndroidProguardConfigTest`, `AndroidResourceShrinkTest`,
  `AndroidCoreLibraryDesugaringTest`). Plus a real
  `mergeResources` (dep android-lib + AAR + app res). Tool ports `Aapt2`/`Dexer`/`ApkSigner` (`tools/`) split by ART reality: **native**
  aapt2/zipalign via ProcessBuilder (`Aapt2Subprocess`); **pure-Java** D8/apksigner either subprocess
  (`D8Dexer`/`ApkSignerTool`, desktop) or **in-process** via their APIs (`D8InProcessDexer` →
  `com.android.tools.r8.D8`, `ApksigSigner` → `com.android.apksig`, the on-device path). Factories
  `AndroidBuildSystem.subprocess(...)` / `.inProcess(...)`; r8+apksig are `compileOnly`+test here, `:ide-android`
  bundles them. **Library-aware:** `AndroidLibraries`+`AarExtractor` route JAR/AAR deps — code→compile/dex,
  AAR res→aapt2(merged into app R), AAR assets→`assets/`, AAR jni→`lib/`. **Multi-module:** compiles the
  whole module-dependency closure and dexes every output; dep android-lib res merged. **Variant-aware:** a
  dependency android-lib is built in the variant matching the app being assembled (`AndroidVariants.
  matchLibraryVariant`: exact name → same build type with dimension-aware flavor match → debuggability fallback
  → the lib's default) — `registerAndroidLibrary` uses that variant's source roots / res / R / classpath, not
  all non-test source sets; the app likewise merges each dep lib's matching-variant res. `AndroidVariant.
  configurations` (the unfiltered candidate names) drives the dep-classpath filter.
  **Kotlin:** when a module (app or android-lib) has `.kt`, `AndroidKotlinCompileTask` runs before its
  `compileJava` (K2 against android.jar + the lib's own non-final R via the injected `KotlinCompile` port);
  the `kotlin-classes` output joins the Java compile classpath and the **project dex scope** (`DexArchiveBuilderTask`
  takes multiple project-class roots), and a lib's Kotlin output sits in the `kotlin-classes` sibling so dependers
  find it. `android-app`/`android-lib` source sets gained a `src/<set>/kotlin` SOURCE root (plus
  `src/<set>/resources` = Java resources and `src/<set>/jniLibs` = native libs; see the packaging note above).
  **Decoupled (reusable) library R:** each android-lib gets a non-final R from its OWN res (`generateR`
  `--non-final-ids` → `compileR`, compile-only, kept OUT of the lib's dexed output → getstatic not inlined);
  the app generates+dexes the FINAL R for all lib pkgs (`--extra-packages`) → lib compiled once, app-independent,
  no dup R. `SampleAndroidProject` (`app` android-app → `feature` android-lib → `core` java-lib) is the
  **Android test fixture** (`bootstrapDemo`/`seedDemo`); editable manifest+res, android.jar SDK (detected on
  desktop, bundled asset on-device). The Java sample is `SampleProject`/`bootstrapJavaDemo` (the Java-test
  fixture). The launchers no longer seed a demo on first run — both start on the project picker. The file tree surfaces the manifest + res/assets (`IdeServices.manifestPath` +
  `treeRootsDetailed`, which keeps each root's source-set name + `ContentRole`s so the tree picks distinct icons).
  Verified on desktop (skip when no SDK): `AndroidApkBuildTest` (both wirings),
  `AndroidLibraryAwareTest` (JAR+AAR), `AndroidMultiModuleTest` (`app→util→core` all 3 dexed),
  `AndroidIncrementalDexTest` (edit app → dexBuilder re-archives only the app; `mergeExtDex` skipped, i.e. lib not re-dexed), `AndroidPerClassDexTest` (edit one of two app classes → only its per-class `.dex` changes), `AndroidMonoDexTest` (minSdk&lt;21 → single `mergeDex`/`classes.dex`), `AndroidLibResourceMergeTest`
  (decoupled-R invariants + res in arsc), `SampleAndroidProjectTest` (sample → signed APK). Registered into the
  host via `AndroidSupport.register(moduleTypes, codecs)`. **Gotchas:** D8 needs a
  jar not a class dir (DexTask jars in-process); D8 rejects bytecode newer than it supports (compile at the
  module languageLevel ≤ 17); `List<Path> + Path` binds the `Iterable` overload (a Path is `Iterable<Path>`)
  — append with `+ listOf(p)`. See `docs/android-support.md`.
- `language-api` (`dev.ide.lang` + `.dom/.incremental/.resolve/.completion`): LanguageBackend SPI,
  SourceAnalyzer/SourceCompiler, CompilationContext; backend-neutral DOM (DomNode/ParsedFile/NodeKind,
  `nodeAt`); IncrementalParser (DocumentSnapshot/DocumentEdit/reparse); resolve (Symbol/Scope/TypeRef);
  completion (CompletionService/CompletionContext/CompletionItem). Also the `platform.languageBackend` EP
  (`LANGUAGE_BACKEND_EP`): backends are contributed, and `ide-core` picks one per file by matching the
  file's `LanguageId` (`.java`→jdt, `.xml`→xml) — adding a language is a registration, not a host edit.
- `lang-xml` (`dev.ide.lang.xml` + `.completion`): the **XML LanguageBackend** (Android layouts/values/
  manifest/drawables/menus). `XmlTreeParser` — a hand-written **error-tolerant** parser (never throws on the
  half-typed buffer; unclosed/mismatched tags recover via an open-name stack with `xml.*` diagnostics) →
  neutral DOM (`XmlNode`/`XmlParsedFile`, `XmlNodeKinds`; `ATTR_VALUE` range is the text *between* quotes).
  Full reparse per change (XML files are small; `INCREMENTAL` not advertised). `XmlSourceAnalyzer` exposes
  the incremental parser + completion + well-formedness diagnostics. **Deliberately Android-AGNOSTIC**:
  `XmlContextScanner` computes *where* the caret is (`XmlCompletionPosition`: TAG_NAME/ATTRIBUTE_NAME/
  ATTRIBUTE_VALUE + tag/parentTag/attr/prefix/range) and `XmlCompletionService` merges candidates from
  injected `XmlCompletionContributor`s — the host supplies *what* belongs there. The Android knowledge lives
  in `AndroidXmlContributor` (ide-core), driven by the **SDK-derived `AndroidSdkMetadata`** (the curated
  `AndroidWidgetCatalog` is GONE) bundled as a classpath asset + merged `ResourceRepository`/index →
  CompletionItems: hierarchy-correct attributes (incl. parent `*_Layout` params like RelativeLayout's
  `layout_below`/`toEndOf`), enum/flag/boolean values, `@type/name` refs filtered by accepted ResourceType,
  `@+id/`. **Custom-view tags:** layout TAG_NAME completion offers framework widgets (simple names) **plus
  custom `View` subclasses discovered from the module's library classpath** (Maven jars + AAR `classes.jar`)
  by their fully-qualified name — `CustomViewScanner` (android-support) ASM-scans the classpath for View
  subclasses (seeded with the bundled SDK widget map so a chain bottoming out at a framework base like
  `android.widget.Button` still resolves; cross-jar ancestry handled), content-fingerprint cached under
  `.platform/caches/custom-views/`. `IdeServices.customViewWidgets` feeds them in; `XmlCompletionService.nameMatches`
  now also matches a candidate's local name after `.` so typing `MaterialButton` completes the FQN.
  **Editor QoL:** auto-close tags (`XmlEditing.tagToClose` — `>` after `<TextView…` inserts
  `</TextView>`) + caret hops past the closing `"` after an attribute-value completion (`CodeEditor.accept`).
  `analyzeXml` (ide-core) merges XML well-formedness with the resource-reference checks into the one
  editor Diagnostic stream. **Phases 1–4 done** + manifest completion + create-XML/-dir + the namespace `:` fix
  + namespace-aware attribute matching; phase 5 (non-layout templates, framework `@android:` resources) in
  `docs/xml-language-support.md`. **Phase 4 inspections/quick-fixes:** pure detection in `XmlLintRules`
  (missing `xmlns:android`, hardcoded string, missing `layout_width`/`height`), `IdeServices.xmlFindings`
  attaches fixes + the index-backed unresolved-resource check (repository fallback while the index builds);
  fixes reach the lightbulb via the XML branch of `editorActions`/`applyEditorAction` — extract-to-`@string`
  appends to `res/values/strings.xml`, create-resource, add-attr, add-namespace. **Phase 3 resource index:**
  `AndroidResourceIndex` (android-support `index/`, on `platform.index`) keyed by resource name →
  `ResourceDeclValue(type,name,file,offset)`, parsing res XML via `ResourceFileScanner`; `IndexScope.resourceRoots`
  + `IndexServiceImpl.indexSource` walks res `.xml` (disjoint from Java inputs — baselines safe); `IdeServices`
  feeds res roots (project+dep+AAR, keyed `"type/name"`), reindexes on `save`; **completion (resource names),
  go-to-def, and the unresolved-resource inspection now resolve via the index** (repository fallback while it
  builds). **Matching:** `XmlCompletionService.nameMatches`
  matches the local name after `:`/`/` (type `layout_w` → `android:layout_width`); the popup bolds the matched
  run wherever it falls (`CompletionPopup`). **`:` fix** (`extraWordChars` in `CompletionSession.kt`/`CodeEditor.kt`)
  + **create XML/dir** (`NewXmlFileDialog` File/Directory modes, `FlowRow` chips, `IdeBackend.createDirectory`). **Phase 2 metadata** (`android-support/metadata/`): `LayoutMetadata` interface,
  top-level `Widget`/`AttributeSpec`, `AttrsXmlParser` (javax.xml; framework + custom attrs.xml),
  `SdkMetadataCodec` (compact text asset `CAMETA1`), `AndroidSdkMetadata` (hierarchy-aware attr inheritance via
  android.jar superclass map; `app:`-prefixed reuse for custom views; `reference`-attr type hints by name).
  The `:android-sdk-metadata` generator (ASM `ClassReader` over android.jar) produces the asset, **committed/bundled**
  at `android-support/src/main/resources/android-sdk-metadata.txt` (API 36); `AndroidSdkMetadata.bundled()` loads
  it (cached), and `IdeServices.sdkLayoutMetadata()` prefers a `<workspace>/.platform/android-sdk-metadata.txt`
  override (e.g. another API level) + merges per-module custom-view attrs (`customAttrsMetadata`). **Manifest:** `AndroidManifestCatalog`
  routed when the file is `AndroidManifest.xml`. **`:` fix:** editor word-boundary logic is language-aware via
  `extraWordChars(path)` (XML adds `:@?+/.-`) in `ide-ui` `CompletionSession.kt`/`CodeEditor.kt` — `android:`/
  `@string/` no longer dismiss the popup; Java unchanged. **Create XML:** `res/` folder tree nodes
  (`TreeNode.resDirPath`) expose `+` → `NewXmlFileDialog` (Layout/Values/Drawable/Menu templates) via the
  existing `createFile` seam. Verified: `lang-xml` parser/completion tests, `android-support`
  `AndroidSdkMetadataTest`, `ide-core` `AndroidXmlCompletionTest` (widget/attr/value/manifest/SDK/custom-view).
- `lang-kotlin` (`dev.ide.lang.kotlin` + `.parse/.symbols/.resolve/.completion`): the **editor-only Kotlin
  LanguageBackend** (`docs/kotlin-completion-backend.md`). Uses the Kotlin compiler ONLY to parse — a
  resolution-free **standalone PSI host** (`KotlinParserHost`: a singleton `KotlinCoreEnvironment` →
  `text → KtFile`, error-tolerant) adapted to the neutral DOM (`KotlinParsedFile`/`KotlinDomNode`,
  `KtElement → NodeKind` in `KotlinNodeKinds`) — and discards all of its resolution/FIR, building its OWN
  symbols/inference/completion on top. The editor backend does NO codegen (that's a separate track — see below).
  **Two symbol sources** (`KotlinSymbolService`): project `.kt` parsed to PSI → declaration index
  (`SourceIndex`, incl. extensions); classpath binaries branch on `@kotlin.Metadata` — Kotlin libs decoded
  via `kotlin-metadata-jvm` (`KotlinMetadata`, ASM-extracts the annotation → real Kotlin shape: extensions,
  properties, nullability), plain Java/Android via ASM bytecode (`JavaBytecode`). Kotlin **mapped types**
  (String/List/… have no `.class`) bridged by a hardcoded Kotlin→Java map + builtin supertype chains
  (`Builtins`); **extension index** keyed by receiver FQN (walks supertypes) makes `list.map`/`string.trim`
  appear on `.`. Resolution + the declared-type-driven **inference subset** (`KotlinResolver`: scopes from
  PSI parents, locals-from-initializers, member/call return types, constructor calls, `a.b().c` chains) →
  member/name/type **completion** (`KotlinCompletionService`, completion-token splice). Default Kotlin
  imports in `DefaultImports`. **Desktop-verified** (`CI_CORE_ONLY=true ./gradlew :lang-kotlin:test`, 12
  tests): parse spike (cold ~210ms/warm ~3ms), `@Metadata` decode vs the real stdlib jar, and end-to-end
  completion (stdlib extensions, source members, inference chain, scope, type, go-to-def). **Gotchas:**
  the compiler/platform is `:kotlin-compiler-deps` — the UNSHADED `-for-ide` split + real `com.intellij.*`
  platform jars (versions `kotlinForIde`/`intellijPlatform` in the catalog, NOT Maven Central; the same world
  the K2 Analysis API links, so ONE PSI everywhere); `createForProduction` needs `@OptIn(K1Deprecation::class)`
  and a `CompilerConfiguration.create(...)`-built configuration (extension storage), and the K1
  `KotlinToJVMBytecodeCompiler` is gone — the registrar compile path drives the K2 `cli.pipeline` phases.
  **Registered in `ide-core`** (`LANGUAGE_BACKEND_EP` + `.kt`/`.kts`→kotlin routing in `languageFor` +
  `indexService`/`extensionCacheDir` injection in `analyzerFor`; analyzer is `Disposable`). The classpath
  extension scan is **lazy + persistent** — per-jar content-keyed `.kxt` cache under
  `.platform/caches/kotlin-ext`, and jars with no `META-INF` `kotlin_module` are skipped without decoding a
  class. **Kotlin codegen is wired into the build** (`compile/`): `KotlinJvmCompiler` (in-process K2
  `K2JVMCompiler`; stdlib located via `Unit::class`, `android.jar` boot + `-no-jdk` on ART, host `jdkHome`
  on desktop). The build drives it through lang-kotlin's OWN `compileKotlin` build task (`build/KotlinCompileTask`,
  which calls `IncrementalKotlinCompiler` directly and applies the Compose plugin when needed) — there is no
  build-engine compile port; jvm-build's `JavaPlugin` registers the task (Java interop both ways; see the
  jvm-build + android-support bullets). Desktop-verified end-to-end (`KotlinJavaInteropBuildTest` in ide-core
  builds + RUNS a mixed module). **Incremental below the task level** (`compile/IncrementalKotlinCompiler` +
  `KotlinAbi`, docs phase 3): `KotlinCompileTask` routes through it — a plain-text manifest
  sidecar (`<kotlin-classes>.ic/`, outside the dir the engine fingerprints) tracks per-source content hashes,
  the source→`.class` map (via `-Xreport-output-files`), and a per-class **ABI snapshot**. A body-only edit
  recompiles just the changed `.kt` (unchanged classes copied out as a read-only binary classpath +
  `-Xfriend-paths` for `internal`), keeping the rest byte-for-byte; any ABI/structural/context change falls
  back to a full module recompile (conservative — no file→file dep graph). `KotlinAbi` hashes the public
  surface only (header + non-private member sigs + `@kotlin.Metadata`; bodies/privates excluded) so a body
  edit is ABI-stable, but a class with an **inline** fun is hashed whole (its body is ABI). Verified
  `IncrementalKotlinCompilerTest`. The ART go/no-go was cleared by the K2-on-ART spike
  (`docs/kotlin-compiler-on-art.md`); running the wired (incremental) build path on device is what's left.
  **Compose-aware editing:** `KotlinResolver.composableContextAt(offset)` decides a position's Compose
  calling-convention status (COMPOSABLE / NON_COMPOSABLE / UNKNOWN) by walking PSI boundaries — a `@Composable`
  function/accessor or a `@Composable`-typed lambda is composable, an `inline` lambda is transparent (inherits
  the enclosing scope), a plain non-inline lambda resets it — faithful to the compiler's `ComposableCallChecker`
  (`isComposable`/`isInline` already ride on `KotlinSymbol`/`KotlinType` from source/metadata/bytecode).
  Completion **boosts** `@Composable` callables to the top in a composable context (Android Studio's weigher;
  a boost in `rank()`, NOT a filter — non-composable code stays available). The diagnostic
  `kt.composableInvocation` (`KotlinSourceAnalyzer.composableInvocation`) flags a `@Composable` call from a
  confidently-non-composable context as an ERROR (the compiler's `COMPOSABLE_INVOCATION`); UNKNOWN backs off,
  so the parse-only model never false-positives. Verified `KotlinComposeContextTest`.
- `deps-api` (`dev.ide.deps`): DependencyResolver (Maven coordinates → jars/aars, conflict policy). `resolve`
  takes a `platforms: List<Coordinate>` of imported BOMs (Gradle `platform(...)`).
- `deps-impl` (`dev.ide.deps.impl`): `MavenDependencyResolver` — fetch/parse POMs (parent chain + imported
  BOM `dependencyManagement` merged), transitive walk with scope-narrowing/exclusions/cycle-guard, conflict
  resolution (`NEWEST`/`PINNED`/`FAIL_ON_CONFLICT`), `.aar`→`classes.jar`+`res`/`assets` explosion, disk cache
  (`.platform/caches/resolved-deps`, cache-first → offline). **BOM platforms:** a `platforms` coordinate
  supplies the version for any versionless (blank-version) coordinate — direct OR transitive — *without*
  overriding an explicit version (plain `platform()` semantics; earlier platforms win). Persisted in the model
  as `PlatformDependency` (a new `OrderEntry`; `{platform = "g:n:v"}` in `module.toml`, no classpath artifact).
  IdeServices wires it: `addPlatform` imports a BOM; `addDependency` accepts a versionless `group:name`
  (resolved against the module's platforms, then **pinned** to the resolved version at add time — a later BOM
  bump needs a re-add). The Dependencies screen has a Library/Platform(BOM) toggle + a direct-add row for
  typed/versionless coordinates. **Gradle Module Metadata variant selection** (`GradleModuleMetadata.kt` +
  `GmmVariantSelector.kt`): when a coordinate publishes a `*.module` the resolver reads it and picks the
  artifact variant by Gradle attributes (`loadNode` → `selectVariant`: hard-filter category/usage/library-
  elements, soft-score `platform.type` androidJvm>jvm>common + `jvm.environment`; usage ladder accepts
  java-*/kotlin-* api>runtime), follows `available-at` (KMP root → `-android` platform module), and uses that
  variant's `dependencies`/`files`. **Download by the file's `url`, not its logical `name`** (AGP publishes a
  KMP AAR as name `…-release.aar` but url `…-android-<v>.aar` — using `name` 404s every KMP AAR). Also honors
  GMM `dependencyConstraints` (atomic-group/BOM alignment applied to GAs in the graph), `version.strictly`
  pins (not bumped by newest-wins), multi-file variants (extra files → `ResolvedArtifact.extraClassesRoots`),
  and a GMM sources variant (KMP `-sources` attach, vs the root-coordinate classifier guess). **Cross-module
  capability conflict resolution** (Gradle-faithful): a module that declares it also provides another's
  capability (guava → `com.google.collections:google-collections`) supersedes it — Gradle picks one (the highest
  capability version, like `selectHighestVersion`) and **evicts the loser entirely** (substituting it with the
  winner). The result is then **pruned to the reachable graph** (an evicted/superseded module's now-unreachable
  transitives drop out), so resolution is accurate rather than relying on a downstream dedup. Falls back to the
  POM when no `.module` exists, so non-GMM libs resolve exactly as before; consumer attributes default to
  Android (`VariantRequest`). This is what `MavenClasspath.dedupeForAndroidDex`'s KMP `-android`/`-jvm` collapse
  used to paper over — that ranker is now removed (only the bundled-stdlib-vs-Maven newest-wins stays).
  `addDependency`/`addModuleDependency`/`addPlatform` take an optional `variant` (a build-variant config like
  `debug` → persisted as a `[dependencies.debug]` declaration). **`search()` (the "Add dependency" picker) queries
  BOTH Maven Central's Solr endpoint AND Google's Maven repo index** (`master-index.xml` + per-group
  `group-index.xml`, crawled + POM-packaging-probed, all session-cached), merging the hits (Central first, deduped
  by group:name) — `androidx.*` / `com.google.android.*` artifacts live ONLY on Google Maven (not mirrored to
  Central), so a Central-only search never surfaced them even though they resolved fine once added. Verified:
  `MavenDependencyResolverTest` (BOM +
  GMM `-android` selection / available-at / name≠url AAR / constraints / strictly / multi-file / kotlin-runtime
  / sources / capability eviction (highest version) + reachable-graph prune / offline / Google-Maven search
  (index match, POM packaging, stable-version preference, full-coordinate paste)), `GradleModuleParserTest`,
  `GmmVariantSelectorTest` + `PersistenceRoundTripTest`.
- `index-api` (`dev.ide.index`): indexing SPI — `IndexExtension`/`IndexService`/`IndexInput`, the
  `platform.index` EP, shared value types + `SourceDeclarationScanner`. See `docs/indexing-infrastructure.md`.
- `index-impl` (`dev.ide.index.impl`): the engine. **Static (SDK+library) side = disk-backed segments**
  (`Segment`/`SegmentWriter`): one immutable `.seg` per artifact (keyed by content hash), queried in place —
  sorted names + varint-delta postings + optional trigram dict, read on demand through a bounded LRU
  `BlockCache` (default 4 MB cap); the only resident state is a sparse term index (every 64th term →
  offset, `String[]`/`long[]`), so heap is flat regardless of index size — the §2 low-RAM design (block-cache
  variant of mmap). **Source side = in-memory `IndexData`** (`TreeMap` + postings + trigram), incremental on
  edit. Shared `Scoring` (prefix/fuzzy, identical ranking); per-artifact cache reused across launches
  (opened, not loaded); `Closeable`/`invalidate()` shut channels. Built-ins `classNames`, `packages`,
  `sourceSymbols`; the bytecode `members` index lives in `lang-jdt` (needs ecj). Completion queries this for
  unimported-class (auto-import) + package completion; palette uses it for go-to-symbol. See
  `docs/indexing-infrastructure.md`.
- `analysis-api` (`dev.ide.analysis`): diagnostics/analyzer/quick-fix SPI — ONE `Diagnostic` model + one
  pipeline (compiler errors and analyzer findings merge into the same stream). `Analyzer` shapes
  `FileAnalyzer`/`ProjectAnalyzer` over tiers `SYNTAX`/`SEMANTIC`/`PROJECT`; `DiagnosticSink`/
  `ProjectDiagnosticSink`, `AnalysisTarget`/`ProjectAnalysisScope`; `QuickFix`/`WorkspaceEdit` (reuses
  `DocumentEdit`)/`QuickFixProvider` (fixes keyed by `Diagnostic.code`, incl. compiler codes);
  `DiagnosticProvider` (the compiler, unified); `AnalysisService`, `AnalysisProfile`, batch `LintReport`;
  EPs `platform.analyzer`/`platform.quickFixProvider`/`platform.diagnosticProvider`. Interfaces only.
  See `docs/analysis-api.md`.
- `analysis-impl` (`dev.ide.analysis.impl`): the engine behind analysis-api. `AnalysisEngine : AnalysisService`
  runs FileAnalyzers (one shared DOM walk → node-kind gating via `interestedIn`), the compiler
  (`DiagnosticProvider`), and ProjectAnalyzers; applies the `AnalysisProfile` (enable/severity) and
  `SuppressionFilter` (`@Suppress` scoped to the enclosing decl via the DOM + `// noinspection` next-line);
  publishes a version-gated set (`PublishedState`, replace-not-append) to `AnalysisListener`s. Per-tier
  debounce + cancellation (`SchedulerConfig`, coroutines); `apply` runs fixes atomically + re-analyzes;
  `lint` is the batch sweep. Host-decoupled via the `AnalysisEnvironment` port (targets/language/apply).
- `block-api` (`dev.ide.block`): the projectional (block) editor SPI — `BlockNode`/`BlockSlot`/`BlockPart`
  (ordered fields + slots)/`SlotCategory`/`BlockTree`, `BlockMapping` + `BlockTemplate` + `ProjectionContext`
  + the `platform.blockMapping` EP, the `BlockEdit` sealed set (`SetField`/`ReplaceWithText`/`Delete`/
  `InsertTemplate`/`Move`/`Wrap`), and `BlockProjectionService` (`project`/`blockAt`/`computeEdit`).
  **Typed slots:** `ValueKind` (BOOLEAN/NUMBER/STRING/OBJECT/TYPE/UNKNOWN) on `BlockSlot` (what the
  position expects) and `BlockNode` (what the expression produces) drives the Scratch-style socket
  shapes; inferred syntactically today, with the `ValueKindOracle` hook (engine asks it FIRST) so a
  semantic resolver can refine later. Blocks are a **live projection of the shared DOM** (not a parallel
  model); a block edit compiles to a minimal `DocumentEdit` so untouched code survives byte-for-byte.
  See `docs/block-based-editing.md`.
- `block-impl` (`dev.ide.block.impl`): the engine. `BlockProjectionEngine` does **gap-carving** projection
  (interleave the literal source between child ranges as read-only chrome with each child projected into a
  slot) + the surgical `BlockEdit→DocumentEdit` pipeline; `JavaBlockMapping` decomposes **statements + key
  expressions** (control flow, calls, member/name refs, infix, literals; containers → one foldable list
  slot), collapsing the rest to editable opaque text slots. **Call collapse:** a pure-name receiver
  (`System.out`) becomes an editable `qualifier` field in the call block's header, and fluent chains
  (`sb.append(x).append(y)`) flatten into ONE method_call block — `name`/`name1`/… fields + one ARGUMENT
  slot per arg, real-range chrome between (cursor gap-walk, so `defaultSerialize` round-trips
  byte-for-byte; never synthesize separator text). **ValueKind inference** is syntactic and pure
  (`valueKindFor`/`expectedValueKind`/`primitiveKind` in JavaBlockMapping.kt): literals/operators/casts
  produce kinds; if/while/do/assert conditions, typed initializers and returns expect them; the engine
  consults a `ValueKindOracle` first. Verified against the real JDT DOM (`BlockProjectionTest`, 12 tests).
  **Gotcha:** projection ids are deterministic per text — `computeEdit`/
  `applyBlockEdit` re-project the *same* buffer to resolve refs and hold no state, so the host must pass the
  text the displayed tree was projected from. Wired into the IDE via `IdeBackend.projectBlocks`/
  `applyBlockEdit` (neutral `UiBlockNode`/`UiBlockEdit` DTOs, both carrying lowercase `valueKind` strings) →
  `IdeServices.projectBlocks`/`computeBlockEdit` (in `ide-core`) over the per-module incremental parser. The
  `ide-ui` block view (`editor/BlockEditor.kt` + `editor/blocks/BlockShapes.kt`/`BlockDrag.kt`/
  `BlockCompletion.kt`/`SlotCompletion.kt`, behind the Code/Blocks toggle) is a **Sketchware/Blockly
  canvas**: solid category-colored puzzle blocks (notch/bump connectors via `GenericShape`, overlapped by
  `InterlockColumn`), C-shaped control blocks wrapping a colored body rail, **typed value sockets**
  (`ValueShape`, Scratch parity: hexagon=boolean, full pill=number, sharp rect=string, outlined tag=type,
  rounded=object/unknown — applied to empty sockets AND the value blocks/pills filling them), **segmented
  chain rendering** (dimmed `qualifier`, bold `name*` fields, thin dividers between chain links),
  **inline completion** while editing a socket/field (reuses the code editor's public `CompletionList` +
  `CompletionSession`; auto-import `additionalEdits` held until commit; socket-type-aware re-ranking via
  `rankForSocket`), an index-backed **palette search** (`searchSymbols`/`searchMembers` → draggable
  template hits), and **drag-and-drop** (`DragState`/`dragSource`/`dropZone`: long-press to
  Move/Delete-on-trash, drag a palette template to Insert) — all mapping to BlockEdits. Verified on desktop (screenshot).
- `ide-ui` (`dev.ide.ui`, Compose Multiplatform `commonMain`, **desktop (JVM) + Android targets**): the
  reusable IDE UI — design-token theme (faithful to `docs/design/tokens.css`), `CaIcons`, components,
  `CodeEditor` (gutter + syntax + completion popup + **inline diagnostics**: severity-colored wavy
  underlines, a gutter error/warning glyph, and the inline error chip; muted for UNUSED), picker/editor
  screens. **File tree** (`FileNavigator`): icons come from the extensible `TreeIcons` registry keyed by
  `TreeNode.iconId` (`TreeIcon.Glyph`/`Folder`/`Badge`, theme-resolved `IconTint`); distinct source-set
  icons (java/resources/android-res/assets/generated), **compacted middle packages** (`com.tyron.codeassist`
  one row, segments kept), and a **New-Class flow** — hover `+` on a source-root/package row (or the header
  `+`) opens `NewFileDialog`, whose package chips can target an intermediate level of a compacted package;
  it calls `IdeBackend.createFile` then `IdeUiState.refreshTree()`+opens. `EditorScreen` debounces
  `IdeBackend.analyze` per edit and feeds the result to `CodeEditor`.
  Top bar has a **Re-index** button (`IdeBackend.reindex` → `IndexService.invalidate` + rebuild) and a **Run
  split-button**: the chevron opens a searchable dropdown of `IdeBackend.runTasks()` (`RunControl` in
  `EditorChrome`); `runBuild()` runs the default. `IdeServices.runTasks`/`runTask` enumerate a Java `run` +
  Android `assemble<Variant>` (the latter via `AndroidBuildSystem.subprocess` when an SDK is installed).
  The **block editor** (Code/Blocks toggle; details under the `block-impl` bullet) has typed Scratch-style
  sockets, segmented chain rendering, inline socket/field completion (reusing `CompletionList`/
  `CompletionSession` + `rankForSocket`), and index-backed palette search.
  A `PermissionDialog` (hosted app-wide in `CodeAssistApp`) overlays a running program when the run sandbox
  blocks on a guarded call (`IdeBackend.permissionRequest` → Allow once/run/always/Deny → `answerPermission`).
  On mobile the file-tree + build-console sheets start **closed** (`AppState` keys their defaults off
  `isMobilePlatform`; the console auto-opens on Run); desktop keeps them open.
  Talks ONLY to the `IdeBackend` port (`dev.ide.ui.backend`, paths as strings) — no framework deps. See `docs/design/`.
  **Localization:** all user-facing UI text is a Compose-resources string key in
  `ide-ui/src/commonMain/composeResources/values/strings.xml` (translations in `values-<lang>/`, e.g.
  `values-zh/`; missing keys fall back to `values/`), read with `stringResource(Res.string.<key>)` /
  `pluralStringResource(...)` (`%1$s`/`%1$d` format args, `<plurals>` for counts). NEVER hardcode a visible
  literal in a composable; generic labels reuse a bare shared key, feature-specific ones take a short prefix
  (`dep_`, `run_`, …). Identifiers/route+setting keys/log lines/backend data are NOT localized. See
  `docs/localization.md`.
- `ide-core` (`dev.ide.core`): the shared engine→UI bridge BOTH launchers run. `IdeServices` façade
  (platform-core + project-model-impl + lang-jdt + index-impl + analysis-impl + block-impl + build-engine +
  android-support) + `IdeServicesBackend` implementing `IdeBackend`. Wires the `AnalysisEngine` over an
  `IdeAnalysisEnvironment` (live-overlay targets) with the JDT compiler as a `DiagnosticProvider` + built-in
  Java analyzers (`SystemOutCallAnalyzer`, `UnusedImportAnalyzer` in `JavaAnalyzers.kt`); `IdeBackend.analyze`
  routes through `engine.analyzeNow`. Test fixtures only (the launchers seed nothing): `bootstrapDemo`/`seedDemo`
  (the Android sample) and `bootstrapJavaDemo` (the Java sample).
  Hosts the run sandbox's `PermissionBroker` (blocks a running program on a guarded call → `IdeBackend`'s
  `permissionRequest`/`answerPermission`; remember-logic in the testable `PermissionPolicy` — once/run/
  always, persisted per project), set on `Guards.broker` around each run. The android Run (`androidRun:`)
  assembles then installs+launches via an injected `ApkInstaller` (on-device only).
- `ide-desktop` (`dev.ide.desktop`): JVM Compose launcher — `main()` opens a `ProjectManager` over
  `~/.codeassist/projects` and renders `CodeAssistApp` over an `IdeServicesBackend` (starting on the project
  picker; no demo is seeded). The old Swing/FlatLaf UI is gone.
- `ide-android` (`dev.ide.android`): Android Compose launcher — the device counterpart to `ide-desktop`,
  running the **real** engine on ART (the old in-memory `AndroidIdeBackend` demo is gone). `AndroidIde.bootstrap`
  copies the bundled `android.jar` asset and opens a `ProjectManager.onDevice` off the main thread (starting on
  the picker; no demo is seeded); `MainActivity` renders the same `CodeAssistApp` (`:ide-ui` commonMain) over `ide-core`'s
  `IdeServicesBackend`. Supplies the on-device ports: `VmProgramInterpreter(peerFactory = DexPeerFactory())`
  (the bytecode-interpreter console runner) and
  `ApkInstallerImpl` (`PackageInstaller` + the OS install-confirmation, then launch — needs
  `REQUEST_INSTALL_PACKAGES`). Build: `./gradlew :ide-android:assembleDebug` (AGP 9.x; needs the Android SDK — see build notes).

## Conventions (follow these in `*-impl`)
- IDs are `@JvmInline value class` wrappers — no stringly-typed APIs.
- Open/extensible classifications are string-backed value classes (`NodeKind`, `BuildSystemId`,
  `LanguageId`); closed sets are enums (`DependencyScope`, `SymbolKind`).
- Long-running entry points are `suspend`; only touch the model/DOM inside `ActivityScope.readAction`/
  `writeAction`. Mutate via `*Transaction`/`Modifiable*` then `commit()` (atomic, publishes events).
- Editor features target the **neutral** DomNode/Symbol/Scope — never JDT/javac types directly.
- Default language + compile backend is **Eclipse JDT/ecj** (error recovery, working-copy reconcile,
  built-in completion, light on ART); javac is an optional compile backend; custom parser slots in later.

## Build / verify
Pure-Kotlin, stdlib-only (a `suspend` modifier on an interface method needs no coroutines dependency).
No Gradle wiring is committed yet. Either add `settings.gradle.kts` + per-module `build.gradle.kts`
following the dependency direction above, or type-check the whole scaffold as one unit:
```
kotlinc $(find . -name '*.kt') -d build/scaffold.jar
```
(Ask before committing Gradle files — you may want your own group/version conventions.)

**Regression suite** (opt-in, NOT in `check`): `./gradlew :lang-jdt:regressionTest :index-impl:regressionTest
:jvm-build:regressionTest` runs the `@Tag("regression")` suites against committed JSON baselines under
`<module>/baselines/`, failing on a regression: completion quality (recall/top-1/top-5/MRR), per-keystroke
ns/op + alloc/op, per-file retained heap + a long-session leak guard, completion **at scale** (large project
+ real-jar classpath), and the IDE build engine compiling/running a **large multi-module Java project**
incrementally (the dogfooding milestone). Shared harness in `:bench-support` (testImplementation only).
Re-record a deliberate change with `-Dbench.updateBaselines=true` and commit the diff. CI
(`.github/workflows/ci.yml`) runs the unit tests + quality + build-at-scale gates on the pure-JVM framework
(`CI_CORE_ONLY=true` drops the Android/Compose shells so no SDK is needed). See
`docs/completion-regression-benchmarks.md` (incl. the self-hosting goal + gap analysis).

## Implementation roadmap (foundation first; each step ships against the prior step's interfaces)
1. `platform-core` impl — extension registry, message bus, model read/write lock, activities/progress.
2. `project-model-impl` — model objects, modifiable-model transaction, `module.toml` load/save,
   crash-safe writes. **Exit test:** build a 3-module project in code, save, reload → identical snapshot.
3. Graphs + classpath assembly — api/implementation export rules + content hashing.
   **Exit test:** an `implementation` dep of a dep is absent from the depender's compile classpath; `api` is present.
4. `build-engine` — Task/TaskGraph/TaskExecutor, input/output fingerprints, up-to-date checks, cache,
   bounded parallel. **Exit test:** re-running an unchanged graph does zero work; one input change re-runs only the affected subgraph.
5. Native build system, **Java first** (`compileJava → jar`) on a JDT compiler backend.
   **Exit test:** build & run a multi-module Java CLI on device.
6. Language backend SPI + JDT analyzer — tolerant DOM, diagnostics, bindings, completion.
   **Exit test:** cross-module go-to-definition via the neutral DOM; basic Ctrl-Space completion.
7. Native Android pipeline — aapt2/resources, R, D8/R8 dex, package, sign; AndroidFacet, variants.
   **Exit test:** build + install + launch a debug APK on device. **In progress** (`:android-support`):
   model layer + `AndroidBuildSystem` DAG done and proven on the desktop JVM (one-module app → real signed
   APK via native aapt2 + JDT + D8 + apksigner). Left: on-device install/launch, multi-module dexing,
   `android-lib`→AAR, IDE Run-button wiring. See `docs/android-support.md`.
8. Dependency resolver — Maven/POM transitive resolution, conflict policy, aar extraction, offline cache.
   **Done** (`:deps-impl`): transitive walk, three conflict policies, AAR explosion, disk cache, and BOM
   **platform** support (versionless deps resolved against imported BOMs; `PlatformDependency` in the model +
   the Dependencies-screen Library/Platform flow). Remaining: version ranges/SNAPSHOTs, authenticated repos,
   LRU cache eviction.
9. Gradle compat sync — tolerant parser for `settings/build.gradle(.kts)` + version catalogs → model + sync report.
10. Workspace coordinator + composite builds — parallel/sequential, dependency substitution across linked projects (mixed build systems).
11. Later, behind existing contracts: javac backend, custom parser, Kotlin support, incremental indexing, refactoring, NDK.
    **Refactoring — rename (Java) done:** `JdtRename` (lang-jdt `rename/`) resolves the symbol under the caret
    to a JDT binding key (a constructor maps to its class; locals/params/type-params are file-local) and walks
    `SimpleName`s in a parsed file matching that key (+ the constructor-name special-case for a class rename) →
    identifier ranges. `IdeServices.prepareRename`/`rename` orchestrate project-wide: validate the new
    identifier, pre-filter project `.java` files by substring then parse each with its module analyzer, build a
    multi-file edit, write disk + the editor overlay, and rename the backing `.java` file when a top-level
    public type's name matched it. Exposed as `IdeBackend.prepareRename`/`rename`; the editor triggers it with
    **F2 / Shift-F6** (a prompt in `CodeEditor`), and `IdeUiState.reloadAfterRename` refreshes open tabs.
    Verified `JdtRenameTest` (class across files + constructor, method, field, file-local var vs. shadowed
    field). **Not yet:** override-hierarchy rename, move-class-to-another-package, Kotlin rename.

## START HERE
Implement `platform-core`, then `project-model-impl`, behind the existing `*-api` interfaces, with
unit tests for steps 1–3's exit criteria. Don't change the `*-api` contracts without updating
`docs/architecture-core-foundation.md`.
