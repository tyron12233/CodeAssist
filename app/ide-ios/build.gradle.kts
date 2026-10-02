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

// What a preview runs on, as JVM jars the VM interprets: the desktop preview pipeline (:interp-compose and
// everything it needs at run time) and a Compose UI to draw with. The test writes their paths into a source
// file, since the simulator inherits no environment from Gradle but does share the host's file system.
val previewRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
dependencies {
    previewRuntime(project(":interp-compose"))
    previewRuntime(compose.ui)
    previewRuntime(compose.foundation)
    previewRuntime(compose.material3)
}

// The preview runtime the app bundle carries: this IDE's own interpreter, as JVM classes for the VM. One jar of
// our classes, cut down to the packages a preview loads (lang-kotlin's jar alone is mostly the editor, which the
// VM never touches), plus the ASM jars the interpreter generates peer classes with. Compose, coroutines and the
// standard library are NOT here: those come from the project's own dependencies (IosPreviewDependencies).
val previewRuntimeJar by tasks.registering(Jar::class) {
    archiveFileName.set("preview-runtime.jar")
    destinationDirectory.set(layout.buildDirectory.dir("preview-runtime"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val ours = previewRuntime.incoming.artifactView {
        componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
    }.files
    from(ours.elements.map { files -> files.map { zipTree(it) } }) {
        include(
            "dev/ide/interp/**",
            "dev/ide/jvm/**",
            "dev/ide/lang/kotlin/interp/**",
            "dev/ide/lang/kotlin/symbols/KotlinType*.class",
            "dev/ide/lang/resolve/**",
            "dev/ide/platform/**",
        )
    }
}

/** The jars of the runtime: [previewRuntimeJar] and the external libraries the interpreter itself needs. */
val previewRuntimeLibraries = previewRuntime.incoming.artifactView {
    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.group == "org.ow2.asm" }
}.files

// Called from the Xcode build phase, like `syncComposeResourcesForIos`: copies the runtime into the app bundle,
// where IosPreviewRuntime finds it.
val syncPreviewRuntimeForIos by tasks.registering(Copy::class) {
    from(previewRuntimeJar)
    from(previewRuntimeLibraries)
    val products = System.getenv("BUILT_PRODUCTS_DIR")
    val resources = System.getenv("UNLOCALIZED_RESOURCES_FOLDER_PATH") ?: System.getenv("CONTENTS_FOLDER_PATH")
    into(if (products != null && resources != null) "$products/$resources/preview-runtime" else layout.buildDirectory.dir("preview-runtime-bundle").get().asFile.path)
}

val generatePreviewTestClasspath by tasks.registering {
    inputs.files(previewRuntime, previewRuntimeJar)
    val out = layout.buildDirectory.dir("generated/previewTestClasspath/kotlin")
    outputs.dir(out)
    val libraries = previewRuntime.incoming.artifactView {
        componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
    }.files
    doLast {
        // What the app runs a preview on: the bundled runtime, then the libraries a project would resolve.
        val jars = (listOf(previewRuntimeJar.get().archiveFile.get().asFile.absolutePath) +
            libraries.files.map { it.absolutePath }.filterNot { "kotlin-reflect" in it }.sorted())
        val file = out.get().file("dev/ide/ios/PreviewTestClasspath.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package dev.ide.ios")
            appendLine()
            appendLine("/** Generated by :ide-ios's build: the JVM jars a preview runs on in the VM. */")
            appendLine("internal object PreviewTestClasspath {")
            appendLine("    val jars: List<String> = listOf(")
            for (j in jars) appendLine("        \"" + j.replace("\\", "/") + "\",")
            appendLine("    )")
            appendLine("    val outputDir: String = \"" + layout.buildDirectory.dir("preview-test-output").get().asFile.absolutePath + "\"")
            appendLine("}")
        })
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
            // Crc32, so a test can build a real `.aar` in memory and publish it to the fixture repository.
            // The same archive reader the AAR exploder and every jar read on this platform already go through.
            implementation(project(":kotlin-classfile"))
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
            // The model mapped onto the UI's module/dependency contracts: the same reading of a module and
            // the same writing of an edit the desktop and Android hosts do, so a setting saved here means
            // what it means there. What stays in this host is where a jar comes from and what the standard
            // library counts as.
            implementation(project(":model-bridge"))
            // Compose preview: the closed-world VM the desktop preview pipeline runs inside, with the project's
            // libraries, since nothing here can load them as code (see IosPreviewRenderer).
            implementation(project(":jvm-vm"))
        }
        commonTest { kotlin.srcDir(generatePreviewTestClasspath) }
    }
}
