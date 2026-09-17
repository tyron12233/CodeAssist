rootProject.name = "codeassist"

pluginManagement {
    // build-logic hosts plugins that depend on AGP (the `dev.ide.kotlinc-art` Kotlin-compiler-on-ART
    // instrumentation) — they must share AGP's classloader, which buildSrc can't provide.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories; all resolution flows through here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google() // Compose Multiplatform / AndroidX artifacts
        // :kotlin-compiler-deps only: the unshaded `-for-ide` compiler and the un-relocated IntelliJ platform
        // it needs. Not on Maven Central; JetBrains-only repos. Scoped so normal resolution never consults them.
        maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies") {
            content { includeGroup("org.jetbrains.kotlin") }
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.intellij.*")
                includeGroup("org.jetbrains.kotlin")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm") // JB coroutines fork (…-intellij-N)
                // The syntax-tree builder the Kotlin compiler's multiplatform parser is written against,
                // vendored into :kotlin-syntax. Not on Maven Central, and one of the few IntelliJ artifacts
                // published for Apple targets, which is what lets that parser reach iOS at all. The regex
                // also admits its per-target siblings (syntax-api-jvm, -iosarm64, -iossimulatorarm64): a
                // multiplatform artifact publishes each variant as its own module, so a filter naming only
                // the root coordinate resolves the metadata and then finds no jar.
                includeModuleByRegex("org\\.jetbrains", "syntax-api.*")
            }
        }
        maven("https://cache-redirector.jetbrains.com/intellij-repository/releases") {
            content {
                includeGroupByRegex("com\\.jetbrains\\.intellij.*")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm") // JB coroutines fork
            }
        }
        // AdMob mediation (:ide-android only). The Google-Maven adapter artifacts (com.google.ads.mediation:*)
        // pull each network's underlying SDK, and Pangle/Mintegral host theirs in their OWN Maven repos (not on
        // Maven Central or Google Maven — per Google's mediation setup docs). Scoped by group so normal
        // resolution never consults them. (Meta's SDK IS on Maven Central, so it needs no extra repo.)
        maven("https://artifact.bytedance.com/repository/pangle") {
            content { includeGroupByRegex("com\\.pangle\\..*") }
        }
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") {
            content { includeGroupByRegex("com\\.mbridge\\..*") }
        }
    }
}

// Self-hosting: `generateNativeModel` exports this build for CodeAssist's own build system, and the
// repositories it must record live here rather than on any project (`FAIL_ON_PROJECT_REPOS`), where a
// project-level task cannot read them. Hand them to the root project as `name|url` pairs.
gradle.rootProject {
    extra["nativeModelRepositories"] = dependencyResolutionManagement.repositories
        .filterIsInstance<MavenArtifactRepository>()
        .map { "${it.name}|${it.url}" }
}


// Dependency direction points downward only (acyclic) — see README.md / docs.
//   platform-core  <- vfs-api <- project-model-api <- { build-api, language-api }
//   project-model-api <- deps-api
//   language-api <- { index-api, analysis-api } ; index-api <- analysis-api (diagnostics/analyzers/fixes)
//   language-api <- block-api ; block-api <- block-impl (projectional/block editor)
//   ide-ui (Compose Multiplatform UI) <- { ide-desktop (JVM launcher), ide-android (Android launcher) }

// The pure-Kotlin/JVM framework — builds and tests with no Android SDK or Compose toolchain.
include(
    ":platform-core",
    ":platform-json", // dependency-free JSON reader/writer; multiplatform, so the store transport can use it
    ":vfs-api",
    ":project-model-api",
    ":project-model-impl",
    ":build-api",
    ":build-engine",
    ":android-support",
    ":android-sdk-metadata", // build-time generator: SDK attrs.xml + android.jar → bundled metadata asset
    ":applog-runtime", // tiny Java runtime injected into DEBUG apps: a ContentProvider that forwards the app's logs to the IDE

    ":language-api",
    ":index-api",
    ":index-impl",
    ":analysis-api",
    ":analysis-impl",
    ":lang-jdt",
    ":lang-java", // IntelliJ-PSI Java LanguageBackend: IntelliJ Java parser + native resolution/inference (replaces lang-jdt in the editor)
    ":lang-xml",
    ":lang-kotlin-index", // pure, compiler-free Kotlin symbol/index layer shared by the Kotlin editor backend
    ":kotlin-compiler-deps", // the ONE unshaded Kotlin compiler + IntelliJ platform dependency set (no embeddable)
    ":intellij-psi-host", // the ONE shared IntelliJ platform env both lang-kotlin + lang-xml parse against
    ":lang-kotlin", // editor-only Kotlin LanguageBackend (PSI parse + our own symbols/inference/completion)
    ":lang-ksp", // KSP2 source generation: KspSourceGenerator (SourceGenerator SPI) runs KotlinSymbolProcessing over a module → generated sources

    ":decompiler", // navigate-into-library: read a classpath class → attached source, else decompile (Vineflower for Java, @Metadata stub for Kotlin)
    ":jvm-build", // JVM-language build system: JavaBuildSystem/JavaPlugin compose lang-jdt+lang-kotlin compile tasks over build-engine
    ":interp-core", // on-device Kotlin interpreter: tree-walks lang-kotlin's ResolvedTree (Compose interpreter, step 3)
    ":interp-api",  // published SPI: the narrowed interpreter surface a plugin runs project code through
    ":interp-impl", // the engine behind it: source sessions over :interp-core, bytecode sessions over :jvm-interp
    ":jvm-interp", // PoC: standalone .class bytecode-interpreting VM + Android/native bridge seam (Play dynamic-code compliance spike)
    ":deps-api",
    ":deps-impl",
    ":vcs-api",  // version-control SPI: repository/branch/commit/status model, the provider EP, accounts + forge ports
    ":vcs-impl", // the Git engine: JGit-backed repository, the GitHub REST/device-flow client, the account store
    ":analytics-api", // opt-in usage-analytics SPI (event model + AnalyticsService/AnalyticsSink ports)
    ":analytics-impl", // the engine: durable batch buffer + Supabase PostgREST sink + scrubbed crash reporter
    ":store-api",  // remote Projects Store SPI: catalog model + catalog/account/submission ports
    ":store-impl", // the engine: Supabase PostgREST catalog source, offline cache, submission packager
    ":block-api",
    ":block-impl",
    ":plugin-api",  // UI extensibility SPI: the lean action model (IdeAction/ActionGroup + places) + EPs
    ":plugin-ui-api", // the UI half of the plugin SPI: what an installed plugin implements to contribute Compose UI
    ":plugin-bom", // the versions a plugin compiles against (SPI + the Compose the IDE provides), as one coordinate
    ":plugin-impl", // ActionManager: resolves UI_ACTION_EP/ACTION_GROUP_EP into places/menus, dispatches
    ":agent-api",   // agentic-coding SPI: provider-neutral LLM client + AgentTool + AgentWorkspace engine port
    ":agent-impl",  // the agent engine: OkHttp/SSE transport, Anthropic/OpenAI/Gemini providers, loop, built-in tools
    ":agent-mcp",   // Model Context Protocol server: exposes the agent's tools over stdio JSON-RPC to external clients
    ":layout-preview-api",  // owned XML-layout preview: render contracts (RCanvas/RenderNode/Renderer), android-free
    ":layout-preview-impl", // the preview engine: resource value resolver, inflater, built-in renderers, ASM bridge remapper
    // The platform-free core of the SPI: the symbol model, the DOM, VirtualFile and ContentHash.
    // Multiplatform, because a model that cannot be named from common code cannot be built there.
    ":model-api",
    // Reading a classpath with no JVM: jars, `.class` files and the Kotlin metadata inside them. Graduated
    // out of `experimental` when :lang-kotlin-index started decoding through it instead of through ASM,
    // kotlin-metadata-jvm and java.util.zip, which is what it was written to replace.
    ":kotlin-classfile",
    ":awt-toolkit", // owned java.awt/javax.swing over RCanvas + the ASM remapper that points a program at it
    ":bench-support", // test-only: shared regression/benchmark harness (consumed via testImplementation)
    ":test-support",  // test-only: shared fixtures/infrastructure (temp dirs, stubs, jars, contexts) — auto-wired

    // EXPERIMENTAL, and depended on by nothing. A portable Kotlin lexer/parser that mirrors the
    // compiler's own token + element vocabulary and PSI shape, so the editor backend could one day parse
    // without the JVM-only compiler. It stays out of every other module's dependency list until its
    // differential suites (ours vs. the real compiler, over this repo's sources) are green.
    ":kotlin-syntax",
    // And the question those two exist to answer: does :lang-kotlin-index actually port? A portable
    // rewrite of its bytecode-to-symbol layer on top of :kotlin-classfile, diffed symbol for symbol
    // against the real one over android.jar. A decoder agreeing with ASM's API is not the same claim as
    // the CONSUMER producing the same symbols.
    ":kotlin-symbols",
)

// The IDE shells (Compose Multiplatform + AGP). These apply the Android Gradle plugin / Compose KMP plugin,
// which require the Android SDK even to *configure*. CI sets CI_CORE_ONLY=true to build just the framework
// above (the part with the unit tests + regression suites) without provisioning an Android SDK; a normal
// local build leaves it unset and includes everything. Nothing in the framework depends on these, so
// excluding them is safe and keeps the acyclic graph intact.
if (System.getenv("CI_CORE_ONLY") != "true") {
    include(
        ":interp-compose", // Compose bridge + render surface (KMP: desktop+android) — needs the Compose plugin
        ":ide-ui-api", // neutral IdeBackend port + DTOs + UI-contribution model, shared by :ide-ui and :ide-core
        // The store transport mapped onto the UI's StoreService contract, shared by every host. It belongs
        // to the shells rather than the framework because it api-exposes :ide-ui-api's DTOs, and that module
        // is not in the core set: leaving it above makes the CI_CORE_ONLY build fail to CONFIGURE.
        ":store-bridge",
        ":ide-ui-resources", // the fonts/drawables/i18n strings the UI modules share, and the one `Res` class over them
        ":ide-ui-core", // theme + platform expect/actual + the editor document model + app state: what the UI layers share
        ":ide-ui-components", // the reusable widgets (+ the shared Markdown renderer and the ad slot)
        ":ide-ui-editor", // the code editor: canvas, completion, blocks, folding chrome, preview panes
        ":ide-ui-screens", // the destinations: home, store, Learn, challenges, settings, run/logs, managers
        ":ide-ui-testing", // test-only: StubBackend, the empty IdeBackend every UI layer's tests render against
        ":ide-ui",
        ":agent-ui", // the AI agent's Compose UI as a self-contained plugin module (chat panel + provider sheet + permission overlay)
        ":vcs-ui", // the version-control Compose UI as a self-contained plugin module (Git panel, branches, history, sign-in, clone)
        ":ide-core",
        ":ide-desktop",
        // The iOS shell: a Compose `UIViewController` over :ide-ui, the third host beside desktop and
        // Android. It builds only on a machine with Xcode, which is every machine that can target iOS.
        ":ide-ios",
        // The headless launcher: `codeassist assemble` over :ide-core's HeadlessEngine. A shell like the
        // other two (it depends on :ide-core, which api-exposes the Compose UI port), so it lives here
        // rather than in the framework.
        ":build-cli",
        // JDK/ART compatibility jars the APK dexes but nothing compiles against (relocated ecj + Eclipse
        // runtime, javax.xml.stream / javax.swing / javax.management / javax.lang.model surface, jdk.jfr
        // shims). Consumed only by :ide-android, and it needs the Android SDK to compile the shims against
        // android.jar, so it belongs to the shells rather than the framework. It exists as a module so those
        // jars arrive as ordinary dexable artifacts instead of `files(...)` dependencies — see its build
        // script for why that is worth a module.
        ":art-compat",
        ":ide-android",
        // A plugin packaged as its own app, built here so it cannot drift from the SPI it compiles against.
        ":samples:hello-plugin",
        ":samples:ndk-plugin",
    )
}

// ---------------------------------------------------------------------------------------------------
// Where each module lives on disk.
//
// Modules are grouped into layer directories, but their Gradle paths stay FLAT: the Kotlin editor
// backend is `:lang-kotlin` at `lang/lang-kotlin`, never `:lang:kotlin`. Every `project(":x")`
// dependency, `./gradlew :x:test` invocation, CI task path and published coordinate therefore reads
// the same before and after the grouping. This block is the only place the two are tied together.
//
// A module listed in the `include(...)` calls above but missing here is still expected at the repo
// root; the `require` below turns that into a clear message rather than a missing-directory error.
val layers = mapOf(
    // The framework's foundation: services, the virtual file system, the project/module model.
    "platform" to listOf(
        "model-api", "platform-core", "platform-json", "vfs-api", "project-model-api",
        "project-model-impl",
    ),
    // Everything that reads source: the language SPI, indexes, analysis, the per-language backends,
    // the compiler/PSI hosts they parse against, and the block (projectional) editor over them.
    "lang" to listOf(
        "kotlin-classfile",
        "language-api", "index-api", "index-impl", "analysis-api", "analysis-impl",
        "lang-jdt", "lang-java", "lang-kotlin", "lang-kotlin-index", "lang-ksp", "lang-xml",
        "kotlin-compiler-deps", "intellij-psi-host", "decompiler", "block-api", "block-impl",
    ),
    // The build system. Named `build-system` rather than `build` because `build/` is the root
    // project's own Gradle output directory.
    "build-system" to listOf("build-api", "build-engine", "jvm-build"),
    // Executing project code on device: the source interpreter, the bytecode VM, and the AWT/Swing
    // surface an interpreted program draws into.
    "run" to listOf("interp-api", "interp-core", "interp-impl", "interp-compose", "jvm-interp", "awt-toolkit"),
    // Android as a target platform: the facet/variant model, the SDK metadata, the XML layout
    // preview, and the ART compatibility shims.
    "android" to listOf(
        "android-support", "android-sdk-metadata", "art-compat",
        "layout-preview-api", "layout-preview-impl", "applog-runtime",
    ),
    // Cross-cutting services, each an api/impl pair (plus its own Compose UI where it has one):
    // dependency resolution, version control, the Projects Store, analytics, the AI agent.
    "services" to listOf(
        "deps-api", "deps-impl", "vcs-api", "vcs-impl", "vcs-ui",
        "store-api", "store-impl", "store-bridge",
        "analytics-api", "analytics-impl", "agent-api", "agent-impl", "agent-mcp", "agent-ui",
    ),
    // The plugin SPI a third-party plugin compiles against, and the host that resolves it.
    "plugins" to listOf("plugin-api", "plugin-ui-api", "plugin-bom", "plugin-impl"),
    // The IDE itself: the Compose UI, its backend port, and the desktop/Android/iOS shells.
    "app" to listOf(
        "ide-ui-api", "ide-ui-resources", "ide-ui-core", "ide-ui-components", "ide-ui-editor",
        "ide-ui-screens", "ide-ui-testing", "ide-ui", "ide-core", "ide-desktop", "ide-android", "ide-ios",
        "build-cli",
    ),
    // Test-only harnesses, consumed via testImplementation.
    "tools" to listOf("test-support", "bench-support"),
    // Work that is not part of the product yet: built and tested by CI, imported by nothing.
    "experimental" to listOf("kotlin-syntax", "kotlin-symbols"),
)

// `samples` holds plugins built as their own apps; it is already a directory, so it needs no mapping.
val unlayered = rootProject.children.map { it.name } - layers.values.flatten().toSet() - setOf("samples")
require(unlayered.isEmpty()) {
    "settings.gradle.kts: $unlayered are included but not assigned a layer directory, so Gradle would " +
        "look for them at the repo root. Add each to the `layers` map above."
}

// Only remap what this build actually included — CI_CORE_ONLY leaves the shells out.
val included = rootProject.children.map { it.name }.toSet()
layers.forEach { (layer, modules) ->
    modules.filter { it in included }.forEach { project(":$it").projectDir = file("$layer/$it") }
}
