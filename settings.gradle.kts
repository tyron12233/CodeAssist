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

dependencyResolutionManagement {
    // Modules must not declare their own repositories; all resolution flows through here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google() // Compose Multiplatform / AndroidX artifacts
    }
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
    ":vfs-api",
    ":project-model-api",
    ":project-model-impl",
    ":build-api",
    ":build-engine",
    ":android-support",
    ":android-sdk-metadata", // build-time generator: SDK attrs.xml + android.jar → bundled metadata asset
    ":language-api",
    ":index-api",
    ":index-impl",
    ":analysis-api",
    ":analysis-impl",
    ":lang-jdt",
    ":lang-xml",
    ":lang-kotlin", // editor-only Kotlin LanguageBackend (PSI parse + our own symbols/inference/completion)
    ":deps-api",
    ":deps-impl",
    ":block-api",
    ":block-impl",
    ":bench-support", // test-only: shared regression/benchmark harness (consumed via testImplementation)
)

// The IDE shells (Compose Multiplatform + AGP). These apply the Android Gradle plugin / Compose KMP plugin,
// which require the Android SDK even to *configure*. CI sets CI_CORE_ONLY=true to build just the framework
// above (the part with the unit tests + regression suites) without provisioning an Android SDK; a normal
// local build leaves it unset and includes everything. Nothing in the framework depends on these, so
// excluding them is safe and keeps the acyclic graph intact.
if (System.getenv("CI_CORE_ONLY") != "true") {
    include(
        ":ide-ui",
        ":ide-core",
        ":ide-desktop",
        ":ide-android",
    )
}
