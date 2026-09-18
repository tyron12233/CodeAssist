// ide-ios — the iOS shell. The third host beside :ide-desktop and :ide-android: it builds an `IdeBackend`,
// hands it to `CodeAssistApp`, and wraps the result in the `UIViewController` an Xcode app loads.
//
// Every target here is iOS, so the shared code lives in `commonMain` rather than an intermediate `iosMain`
// (the default hierarchy template is off project-wide -- see gradle.properties -- and there is no second
// platform to share with anyway).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

// The Supabase endpoint, the same pair :ide-android bakes into its BuildConfig. There is no BuildConfig in
// a Kotlin/Multiplatform module, so it is generated: one file, one object, the same property/env override
// chain (-PSUPABASE_URL / SUPABASE_URL), so a rotated key reaches every host the same way.
//
// The publishable key is safe to ship in an open-source client ONLY because row-level security is what
// actually gates access. An empty URL leaves the store wired but inert, which is what a fork with no
// endpoint of its own should get.
val supabaseUrl = (findProperty("SUPABASE_URL") as String?) ?: System.getenv("SUPABASE_URL")
    ?: "https://lqlpkeummmmglikumotx.supabase.co"
val supabaseKey = (findProperty("SUPABASE_KEY") as String?) ?: System.getenv("SUPABASE_KEY")
    ?: "sb_publishable_5T14bUAG6fOGz47kwYzG7A_25dj3ap4"

val generateStoreConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/storeConfig/kotlin")
    // Declared as inputs so a changed endpoint regenerates rather than being served from the build cache.
    inputs.property("url", supabaseUrl)
    inputs.property("key", supabaseKey)
    inputs.property("build", project.version.toString())
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/ide/ios/store/StoreConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package dev.ide.ios.store

            /** Generated from Gradle properties; see app/ide-ios/build.gradle.kts. Do not edit. */
            internal object StoreConfig {
                const val SUPABASE_URL: String = "$supabaseUrl"
                const val SUPABASE_KEY: String = "$supabaseKey"
                const val APP_VERSION: String = "${project.version}"
            }

            """.trimIndent(),
        )
    }
}

kotlin {
    // Both iOS targets: the simulator, and the device (arm64) the app is signed and installed onto. They
    // declare the same framework, and Xcode picks the one matching whatever it is building for.
    listOf(iosSimulatorArm64(), iosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "CodeAssistUi"
            // Static: the Xcode app links one archive and has no dynamic framework to embed or sign.
            isStatic = true
            // The entry point is called from Swift, so it must survive dead-code stripping of the klib.
            export(project(":ide-ui"))
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(generateStoreConfig) }

        // The backend's file and project handling is real Foundation IO, so its tests run on the simulator:
        // `./gradlew :ide-ios:iosSimulatorArm64Test`.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test) // the project/file operations are suspend functions
        }

        commonMain.dependencies {
            // `api` so the exported framework carries the shell's surface, not just this module's.
            api(project(":ide-ui"))
            // StubBackend, which `IosBackend` extends so it only implements the concerns this host actually
            // has (files, projects, saving) and inherits the empty/`Unsupported` answers for the rest.
            // `api`, not `implementation`: it is the supertype of a public class here.
            api(project(":ide-ui-testing"))

            // The Projects Store. The transport (:store-impl) and its translation onto the UI contract
            // (:store-bridge) are the same code the Android and desktop hosts run; what this host supplies
            // is where things live (the app container), how a credential is kept (the keychain) and how a
            // browser is opened (ASWebAuthenticationSession).
            implementation(project(":store-api"))
            implementation(project(":store-impl"))
            implementation(project(":store-bridge"))
            // Kotlin syntax analysis that needs no JVM: the compiler's own parser, vendored and built for
            // this target. It backs the outline and code folding.
            implementation(project(":kotlin-syntax"))
            // ...and the analysis above it: the symbol table, the resolver, the inference subset and the
            // completion contributor, all the same code the desktop and Android hosts run. What does NOT
            // come with it is `KotlinSourceAnalyzer`, the adapter binding that half to a build — this host
            // has none, so `IosKotlinAnalysis` drives the symbol service directly.
            implementation(project(":lang-kotlin"))
            // The library half of that analysis: a classpath has to come from somewhere, and on this host
            // there is no bundled jar to extract, so it is fetched. Same resolver the Dependencies screen
            // runs everywhere else.
            implementation(project(":deps-api"))
            implementation(project(":deps-impl"))
            // ...and that classpath as an INDEX. The jars answer a type by name; only an index answers a
            // NAME by prefix, which is what completion and the unresolved checks are made of.
            implementation(project(":index-impl"))
            // The project model, and the templates that author one. A project created here is the same
            // project the desktop and Android hosts create: `.platform/workspace.json` + `module.toml`,
            // written by the same templates through the same scaffold.
            implementation(project(":project-model-api"))
            implementation(project(":project-model-impl"))
            implementation(project(":project-templates"))
        }
    }
}
