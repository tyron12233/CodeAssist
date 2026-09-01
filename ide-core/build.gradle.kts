plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ide-core — the host-agnostic backend that wires the whole framework together (platform-core +
// project-model-impl + lang-jdt + index-impl + analysis-impl + build-engine) and adapts it to the UI's
// `IdeBackend` port. Extracted from :ide-desktop so BOTH launchers share it: :ide-desktop adds only its
// Compose-window `main()`, and :ide-android adds its Activity + on-device bootstrap (android.jar SDK).
// Pure JVM/Kotlin so the Android app can dex it directly (see android-jdt-port notes).
dependencies {
    api(project(":ide-ui")) // IdeBackend + the Ui* DTOs IdeServices/IdeServicesBackend speak

    implementation(project(":platform-core"))
    implementation(project(":project-model-api"))
    implementation(project(":project-model-impl"))
    implementation(project(":language-api"))
    implementation(project(":lang-jdt"))
    implementation(project(":lang-java")) // IntelliJ-PSI Java backend (native resolution/inference); JDT is still the .java default
    implementation(project(":lang-xml")) // XML language backend (Android layouts/values/manifest)
    implementation(project(":lang-kotlin")) // editor-only Kotlin language backend (PSI parse + own completion)
    implementation(project(":lang-ksp")) // KSP2 source generation: KspSourceGenerator + bundled thin runner/processors (Room)
    implementation(project(":decompiler")) // navigate-into-library: attached source, else Vineflower/@Metadata decompile
    implementation(project(":index-api"))
    implementation(project(":index-impl"))
    implementation(project(":analysis-api"))
    implementation(project(":analysis-impl"))
    implementation(project(":block-api"))
    implementation(project(":block-impl")) // projectional (block) editor — DOM→BlockTree + surgical edits
    implementation(project(":plugin-api")) // UI action SPI (IdeAction/ActionGroup + places)
    implementation(project(":plugin-impl")) // ActionManager: resolves the action EPs for the UI surfaces
    implementation(project(":build-api"))
    implementation(project(":build-engine"))
    implementation(project(":jvm-build")) // JavaBuildSystem: composes lang-jdt/lang-kotlin compile tasks over build-engine
    implementation(project(":android-support")) // android-app/-lib module types + AndroidFacet codec
    api(project(":layout-preview-api")) // owned XML-layout preview contracts; `api` because IdeServicesBackend implements LayoutPreviewBackend (public supertype, must be on consumers' classpath)
    implementation(project(":layout-preview-impl")) // the preview engine (inflater + resolver + chrome)
    implementation(project(":deps-api"))
    implementation(project(":deps-impl")) // Maven dependency resolver (download/transitive/conflict)
    implementation(project(":agent-impl")) // the AI coding agent engine (providers, loop, tools)
    implementation(project(":agent-mcp")) // the agent's MCP server (stdio standalone + in-app HTTP, see AgentBackend)
    implementation(project(":agent-ui")) // the agent's Compose UI plugin (AgentUiPlugin) — paired with AgentPlugin in BuiltInPlugins
    implementation(project(":vcs-impl")) // the Git engine (JGit working copy + the GitHub client + the account store)
    implementation(project(":vcs-ui")) // the version-control Compose UI plugin (VcsUiPlugin) — paired with VcsPlugin in BuiltInPlugins
    // Opt-in usage analytics. `api` because AnalyticsService appears in IdeServicesBackend's (public)
    // constructor signature, so a host wiring it (ide-android) needs the type on its compile classpath.
    api(project(":analytics-api"))
    implementation(project(":vfs-api"))

    implementation(libs.kotlinx.coroutines.core)
    // Editor customizations (symbol bar / macros) persist + import/export as JSON via the tree API (no
    // @Serializable / compiler plugin needed).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline harness.
    testImplementation(project(":bench-support"))
    // Bouncy Castle: the keystore-registry test creates a real keystore (KeystoreCrypto.create needs BC at runtime).
    testImplementation(libs.bouncycastle.pkix)
}

// Several tests boot a real IdeServices and build the full library index (android.jar, the JDK, the Android
// SDK sources) in the single shared worker JVM. The index store's NIO buffers are only released when the
// referencing ByteBuffers are collected, so with Gradle's default 512m worker both ceilings run within a few
// MB of full (the heap peaks around 498m, and MaxDirectMemorySize defaults to the max heap). An index build
// then intermittently fails with "Cannot reserve N bytes of direct buffer memory", skips the artifact, and a
// test that needs it waits out its index timeout and fails on the missing symbols. Give the worker real room.
// Scoped to the unit `test` task so regressionTest keeps its own settings.
tasks.named<Test>("test") {
    maxHeapSize = "1536m"
}

// The compile-time java.awt/javax.swing API jar, generated by :awt-toolkit from the owned toolkit itself.
// It ships as an ordinary resource so ONE artifact serves both platforms: the desktop reads it off the
// classpath, and on Android it rides in the APK's java resources, reachable the same way (the bundled sample
// projects already travel like this). SwingApiStubs extracts it on first use and the platform SDK appends it,
// so `import javax.swing.*` resolves even when the module compiles against android.jar.
val swingApiStubs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { swingApiStubs(project(mapOf("path" to ":awt-toolkit", "configuration" to "swingApiStubs"))) }

tasks.named<ProcessResources>("processResources") {
    from(swingApiStubs) { into("swing") }
}
