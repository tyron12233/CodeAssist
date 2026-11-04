// A sample plugin that teaches the IDE a language through a Language Server Protocol server.
//
// The language is Calc (`.calc`): `let name = expression`, `print expression`, `#` comments, and the
// built-ins max/min/abs. The server is ordinary Kotlin in this same APK, run by the plugin as a separate
// process (`dalvikvm64 -cp <this apk>`) and spoken to over stdin/stdout, which is exactly the path a native
// server such as the Dart analysis server would take. Everything the IDE shows for a .calc file comes from
// that process through the host's LSP client; the plugin itself registers a file type, the server, and the
// editor's lexical colouring.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.codeassist.lspdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codeassist.lspdemo"
        // The IDE's own floor: the plugin's engine facet runs inside the IDE's process.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        // Not minified: the server's entry point is found by name from a separate VM, and R8 would have no
        // way to know that.
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":plugin-ui-api"))
    compileOnly(project(":language-api"))
    compileOnly(project(":lsp-api"))
    compileOnly(project(":build-api"))

    // The UI facet only registers an editor language, but `UiRegistration` names Compose types in methods it
    // does not call, so they have to be on the compile classpath.
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)

    // The server runs in its own VM with only this APK on its classpath, so what it uses must be packaged
    // (the IDE's copy is not there). The engine facet, inside the IDE, uses none of it.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":build-api"))
    testImplementation(project(":language-api"))
}
