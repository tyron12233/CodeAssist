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
    // Opt-in usage analytics. `api` because AnalyticsService appears in IdeServicesBackend's (public)
    // constructor signature, so a host wiring it (ide-android) needs the type on its compile classpath.
    api(project(":analytics-api"))
    implementation(project(":vfs-api"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline harness.
    testImplementation(project(":bench-support"))
    // Bouncy Castle: the keystore-registry test creates a real keystore (KeystoreCrypto.create needs BC at runtime).
    testImplementation(libs.bouncycastle.pkix)
}
