import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Compose resources the IDE UI is built from — the bundled fonts (JetBrains Mono, Material Symbols),
// the sample-project preview drawables, and the i18n strings for every locale — and nothing else. No
// Kotlin source lives here.
//
// It is a module of its own because the UI it serves is several modules (:ide-ui-components,
// :ide-ui-editor, :ide-ui-screens, :ide-ui), and the Compose resources plugin generates one `Res` class
// per module: leaving the resources inside any one of them would make the other three unable to read a
// string. `packageOfResClass` is pinned to the package the accessors were always generated into, so every
// `import dev.ide.ui.generated.resources.*` in the UI keeps resolving unchanged.
//
// Unlike :vcs-ui and :agent-ui — self-contained modules that own both their resources and the UI reading
// them, so their `Res` stays internal — this module's whole purpose is to be read from elsewhere, hence
// `publicResClass = true`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    // Nothing here is @Composable — the generated accessors are plain `val`s over StringResource /
    // FontResource / DrawableResource — but the Compose Multiplatform plugin refuses to configure without
    // the Compose compiler plugin alongside it, and that plugin in turn refuses to run without a
    // compose-runtime on the compile classpath. Hence both, and the `compose.runtime` below.
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "dev.ide.ui.resources"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: the generated accessors expose org.jetbrains.compose.resources
            // types (Res, StringResource, FontResource) in their signatures, so every consumer needs them
            // on its own compile classpath.
            api(compose.components.resources)
            // Only to satisfy the Compose compiler plugin's classpath check (see the plugins block).
            implementation(compose.runtime)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.ide.ui.generated.resources"
    generateResClass = always
}
