plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// build-cli — the headless launcher: assemble a CodeAssist project from a terminal (or from CI) with the
// IDE's own build system, no Gradle and no AGP. It is a third host next to :ide-desktop and :ide-android,
// and the thinnest of the three: argument parsing, a progress transcript, and the GitHub Actions workflow
// commands. All the work happens in :ide-core's HeadlessEngine, which is the same engine the on-device
// `:build` daemon drives.
//
// `installDist` produces the distribution the `codeassist-build` GitHub Action downloads and runs; see
// docs/github-action.md and .github/workflows/cli.yml.
dependencies {
    implementation(project(":ide-core")) // HeadlessEngine + the build DTOs it reports

    implementation(libs.kotlinx.coroutines.core)

    // The two tools android-support keeps `compileOnly` (so the framework stays dependency-light) and each
    // host bundles for itself — exactly as :ide-desktop does. bundletool builds the `.aab` in-process, and
    // Bouncy Castle backs in-process keystore creation, which a first build needs to mint the debug key.
    implementation(libs.android.bundletool)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.kotlinx.coroutines.test)
}

// `codeassist --version` reads this back off the jar manifest. The release workflow passes the IDE's
// release version (`-PcliVersion=3.18.2`); a local build falls back to the framework version.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "CodeAssist build CLI",
            "Implementation-Version" to (providers.gradleProperty("cliVersion").orNull ?: project.version),
        )
    }
}

application {
    mainClass.set("dev.ide.buildcli.MainKt")
    applicationName = "codeassist"
    applicationDefaultJvmArgs = listOf(
        // K2 and R8 run in this JVM; the runner default (1/4 of RAM) leaves a cold Compose build short.
        "-Xmx4g",
        // Nothing here draws, and on macOS an AWT touch would turn the build into a foreground GUI app.
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8",
    )
}
