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
    implementation(project(":lang-xml")) // XML language backend (Android layouts/values/manifest)
    implementation(project(":lang-kotlin")) // editor-only Kotlin language backend (PSI parse + own completion)
    implementation(project(":index-api"))
    implementation(project(":index-impl"))
    implementation(project(":analysis-api"))
    implementation(project(":analysis-impl"))
    implementation(project(":block-api"))
    implementation(project(":block-impl")) // projectional (block) editor — DOM→BlockTree + surgical edits
    implementation(project(":build-api"))
    implementation(project(":build-engine"))
    implementation(project(":android-support")) // android-app/-lib module types + AndroidFacet codec
    implementation(project(":deps-api"))
    implementation(project(":deps-impl")) // Maven dependency resolver (download/transitive/conflict)
    implementation(project(":vfs-api"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
