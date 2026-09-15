import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// store-impl — the engine behind store-api.
//
// Everything Supabase: which RPC answers which port, the JSON each one speaks, the PKCE sign-in, the
// offline catalog cache, and the checks a downloaded payload passes before it is unpacked. All of that is
// in `commonMain`, because it is the same contract whichever host is asking — and the store has three of
// them now (the desktop JVM, ART on Android, and iOS).
//
// What a platform does supply is in `impl/platform`: the socket (`HttpURLConnection` here, `NSURLSession`
// there), the filesystem, SHA-256, and reading a zip. Keeping the seam that narrow is the point — a store
// migration changes one RPC in one place rather than in two clients that have to be kept in step.
//
// Writing a zip is the one capability that is not universal, so packaging a project for submission is a
// port too (`ProjectArchiver`), unavailable on iOS, which is what the publish surfaces already draw.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":store-api"))
            // The dependency-free JSON reader every response goes through.
            implementation(project(":platform-json"))
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            // The logging facade, for transport failures that should reach the console rather than be
            // swallowed. Kotlin/JVM, which is why `storeLog` is a seam rather than a direct call.
            implementation(project(":platform-core"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":test-support"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Declared by hand: the explicit `dependsOn` edges elsewhere in this build turn off the default
        // hierarchy template, so no intermediate apple/ios set is created for us. See app/ide-ui-api.
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
