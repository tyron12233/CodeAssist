// Android launcher: a Compose Android application that renders the reusable IDE UI (:ide-ui) — the same
// commonMain composables the desktop launcher uses — over an Android [AndroidIdeBackend]. It is the
// Android counterpart to :ide-desktop. Under AGP 9, Kotlin is built into `com.android.application` (no
// kotlin-android plugin); Compose comes from the Compose Multiplatform + Compose-compiler plugins.
import com.android.build.gradle.internal.tasks.factory.dependsOn
import dev.ide.build.RelocateTypesInJar
import org.gradle.api.attributes.java.TargetJvmEnvironment
// Imported (not fully-qualified) because the Java plugin's `java` project extension shadows the `java.*`
// package inside a build script — `java.io.File` would parse as `(java extension).io`.
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    // ASM-rewrites the bundled Kotlin compiler so K2JVMCompiler runs on ART (AGP instrumentation, scope
    // ALL — see buildSrc dev.ide.build.kotlinc). No-op until the device spike (KotlinCompilerArtSpikeTest)
    // discovers the first breakage and a pass is added to ArtPatchPasses.
    id("dev.ide.kotlinc-art")
}

// --- ecj-on-ART patch ----------------------------------------------------------------------------
// Eclipse ecj's compiler Parser references java.lang.Runtime$Version, which exists on the JVM but NOT
// on Android's ART (it is absent from android.jar and cannot be stubbed, since app classes may not
// live in java.*). On-device this surfaces as an uncatchable LinkageError that disables editor
// analysis. We resolve ecj on its own and relocate that single reference to a shim we ship
// (dev.ide.lang.jdt.compat.RuntimeVersion in :lang-jdt), then dex the patched jar instead of the
// stock one. Desktop runs on a real JVM and is left untouched.
val ecjUnpatched: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { ecjUnpatched(libs.jdt.ecj) { isTransitive = false } }

val relocateEcjForArt = tasks.register<RelocateTypesInJar>("relocateEcjForArt") {
    // Lazy: `elements` resolves the configuration at execution time, not during configuration.
    inputJar.fileProvider(ecjUnpatched.elements.map { it.single().asFile })
    outputJar.set(layout.buildDirectory.file("ecj-art/ecj-art.jar"))
    renames.put("java/lang/Runtime\$Version", "dev/ide/lang/jdt/compat/RuntimeVersion")
}

// --- Eclipse-runtime-on-ART patch (java.lang.StackWalker) -----------------------------------------
// Eclipse's org.eclipse.core.runtime.Status and org.eclipse.core.internal.runtime.InternalPlatform each
// hold a `static StackWalker` field (StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)) used by the
// caller-class-aware Status.error/info/warning + ILog.get factories. java.lang.StackWalker is a Java-9 API
// absent from ART at our minSdk and (being in java.*) un-stubbable, so on-device that <clinit> throws an
// uncatchable NoClassDefFoundError (java.lang.StackWalker$Option) — and because Status is ubiquitous, it
// disables editor analysis entirely. As with ecj's Runtime$Version, relocate the references onto a shim we
// ship (dev.ide.lang.jdt.compat.StackWalker in :lang-jdt) and dex the patched jars. These two modules
// reach the app only transitively via :ide-core → lang-jdt → jdt.core, so resolve jdt.core's graph here and
// pick the two jars out by name (tracks the `jdt` catalog version automatically). Desktop runs on a real
// JVM and is left untouched. The single `java/lang/StackWalker` rename also covers the nested
// `java/lang/StackWalker$Option` (it shares that prefix).
val eclipseRuntimeUnpatched: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { eclipseRuntimeUnpatched(libs.jdt.core) } // transitive: brings core.runtime + equinox.common

fun eclipseRuntimeJar(prefix: String) =
    eclipseRuntimeUnpatched.elements.map { set -> set.map { it.asFile }.single { it.name.startsWith(prefix) } }

val relocateCoreRuntimeForArt = tasks.register<RelocateTypesInJar>("relocateCoreRuntimeForArt") {
    inputJar.fileProvider(eclipseRuntimeJar("org.eclipse.core.runtime-"))
    outputJar.set(layout.buildDirectory.file("eclipse-art/org.eclipse.core.runtime-art.jar"))
    renames.put("java/lang/StackWalker", "dev/ide/lang/jdt/compat/StackWalker")
}
val relocateEquinoxCommonForArt = tasks.register<RelocateTypesInJar>("relocateEquinoxCommonForArt") {
    inputJar.fileProvider(eclipseRuntimeJar("org.eclipse.equinox.common-"))
    outputJar.set(layout.buildDirectory.file("eclipse-art/org.eclipse.equinox.common-art.jar"))
    renames.put("java/lang/StackWalker", "dev/ide/lang/jdt/compat/StackWalker")
}

// --- kotlin-stdlib asset (on-device Kotlin-compiler spike) ---------------------------------------
// The discovery spike (KotlinCompilerArtSpikeTest) runs K2JVMCompiler on device and needs the Kotlin
// stdlib on its compile -classpath. The app's own stdlib is *dexed* (not a usable .jar at runtime), so we
// stage the stdlib JAR as an asset the test copies to filesDir. Resolved on its own and non-transitive.
val kotlinStdlibArtifact: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { kotlinStdlibArtifact(libs.kotlin.stdlib) { isTransitive = false } }

val bundleKotlinStdlibAsset = tasks.register<Copy>("bundleKotlinStdlibAsset") {
    description = "Stage kotlin-stdlib.jar into a generated assets dir for the on-device Kotlin-compiler spike."
    // Lazy: resolve the configuration at execution time, like relocateEcjForArt above.
    from(kotlinStdlibArtifact.elements.map { it.single().asFile })
    into(layout.buildDirectory.dir("kotlin-stdlib-asset"))
    rename { "kotlin-stdlib.jar" }
}

// --- Compose runtime asset (on-device Compose-compile spike) -------------------------------------
// The Compose-on-ART spike (KotlinCompilerArtSpikeTest.composeCompilesOnArt) compiles a @Composable with
// the Compose plugin and needs the `androidx.compose.runtime.*` shapes on its compile -classpath. The app's
// own compose runtime is dexed (not a usable .jar input), so stage the JVM/desktop runtime JAR as an asset
// (its class signatures are what the plugin's codegen resolves against — Android-specific bodies are
// irrelevant to producing transformed .class). The Compose *plugin* jar itself is the lang-kotlin bundled
// resource (`ComposeCompilerPlugin.jar()`), which works on device, so it needs no separate asset.
val composeRuntimeArtifact: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { composeRuntimeArtifact(libs.compose.runtime.desktop) { isTransitive = false } }

val bundleComposeRuntimeAsset = tasks.register<Copy>("bundleComposeRuntimeAsset") {
    description = "Stage the Compose runtime JAR as an asset for the on-device Compose-compile spike."
    from(composeRuntimeArtifact.elements.map { it.single().asFile })
    into(layout.buildDirectory.dir("compose-runtime-asset"))
    rename { "compose-runtime.jar" }
}

// --- kotlinc resources asset (extension-point descriptors for on-device K2) ----------------------
// The K2 compiler's classes are dexed into the app, but IntelliJ-core boots its extension registry by
// reading XML descriptors (META-INF/extensions/*.xml, plugin.xml, …) from a real filesystem path — a dex
// APK exposes those only as classloader resources, not files. So we ship the compiler's resources as an
// asset; the app extracts it to a dir at runtime and publishes the path in the `kotlinc.art.home` system
// property, which the ASM PathUtil pass reads (see build-logic/.../PathUtilSelfLocatePass). The zip is
// built by :kotlin-compiler-deps (the union of the unshaded platform + `-for-ide` compiler jars' non-class
// entries; it used to be stripped from kotlin-compiler-embeddable).
val kotlincCompilerResources: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { kotlincCompilerResources(project(path = ":kotlin-compiler-deps", configuration = "kotlincResourcesElements")) }

val bundleKotlincResourcesAsset = tasks.register<Copy>("bundleKotlincResourcesAsset") {
    description = "Stage :kotlin-compiler-deps' kotlinc-resources.zip into a generated assets dir."
    from(kotlincCompilerResources)
    into(layout.buildDirectory.dir("kotlinc-resources-asset"))
}

// --- R8 tool dexed as an asset (forked-VM R8 for the release/minify OOM fix) ---------------------
// R8's whole-program pass needs more heap than an app process's `largeHeap` cap (576MB on the test device);
// a command-line VM (dalvikvm) forked from the app is NOT a zygote app process, so its `-Xmx` can exceed
// that cap (measured ceiling ~1.5GB). To run R8 there it needs its classes as a loadable dex — the app's own
// copy is buried in secondary dexes a bare `dalvikvm -cp base.apk` won't load. D8-dex the r8 tool jar into a
// standalone r8.dex.zip asset; `dev.ide.android.ForkedR8Shrinker` extracts it and runs
// `dalvikvm64 -Xmx<n>m -cp <dexes> com.android.tools.r8.R8 …`. (Mirrors what AGP already does to r8 for the app.)
val r8DexTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { r8DexTool(libs.android.r8) { isTransitive = false } }

val bundleR8DexAsset = tasks.register<JavaExec>("bundleR8DexAsset") {
    description = "D8-dex the R8 tool jar into a forked-VM-loadable r8.dex.zip asset."
    val outZip = layout.buildDirectory.file("r8-dex-asset/r8.dex.zip")
    classpath = r8DexTool                       // r8.jar contains D8 — self-dex it
    mainClass.set("com.android.tools.r8.D8")
    inputs.files(r8DexTool)
    outputs.file(outZip)
    // min-api 26 = the app's minSdk; the forked VM runs on the device's ART (>= 26), and a higher min-api
    // minimises desugaring (r8 is plain Java 8 bytecode), so no `--lib` platform is needed to dex it.
    doFirst {
        val out = outZip.get().asFile
        out.parentFile.mkdirs(); out.delete()
        args = listOf(
            "--release",
            "--min-api", "26",
            "--output", out.absolutePath,
            r8DexTool.singleFile.absolutePath,
        )
    }
}

// --- ReflectiveMainLauncher dexed as an asset (forked-dalvikvm entry-point launcher) --------------
// The forked-dalvikvm console runner (dev.ide.android.ForkedDalvikRunner) launches a program through
// dev.ide.build.engine.ReflectiveMainLauncher instead of the VM's built-in main(String[]) lookup, so it can
// start EVERY Kotlin/JVM entry-point shape (fun main() / suspend main / a no-arg @JvmStatic fun main() that
// has no (String[]) bridge / an instance main), not just a static main(String[]). That launcher must sit on
// the forked VM's -cp, but the app's own dexed copy is in secondary dexes a bare `dalvikvm -cp <run dexes>`
// won't load — so compile that ONE pure-Java class and D8 it into a standalone reflective-launcher.dex.zip
// asset the runner extracts and appends to the run container. (Same mechanism as bundleR8DexAsset above.)
// No android.jar bootclasspath: the class references only universal java.lang/reflect/util APIs present on ART.
val compileReflectiveLauncher = tasks.register<JavaCompile>("compileReflectiveLauncher") {
    description = "Compile :build-engine's ReflectiveMainLauncher.java for dexing into the run launcher asset."
    source = files(
        project(":build-engine").layout.projectDirectory
            .file("src/main/java/dev/ide/build/engine/ReflectiveMainLauncher.java")
    ).asFileTree
    classpath = files()
    // Java 8 bytecode: D8 rejects class files newer than it supports (r8.jar is itself plain Java 8 bytecode).
    sourceCompatibility = "8"
    targetCompatibility = "8"
    destinationDirectory.set(layout.buildDirectory.dir("reflective-launcher/classes"))
}
val reflectiveLauncherJar = tasks.register<Jar>("reflectiveLauncherJar") {
    from(compileReflectiveLauncher.flatMap { it.destinationDirectory })
    archiveFileName.set("reflective-launcher.jar")
    destinationDirectory.set(layout.buildDirectory.dir("reflective-launcher"))
}
val bundleReflectiveLauncherDex = tasks.register<JavaExec>("bundleReflectiveLauncherDex") {
    description = "D8-dex ReflectiveMainLauncher into a forked-VM-loadable reflective-launcher.dex.zip asset."
    val outZip = layout.buildDirectory.file("reflective-launcher-asset/reflective-launcher.dex.zip")
    classpath = r8DexTool                       // r8.jar contains D8
    mainClass.set("com.android.tools.r8.D8")
    inputs.files(reflectiveLauncherJar)
    outputs.file(outZip)
    doFirst {
        val out = outZip.get().asFile
        out.parentFile.mkdirs(); out.delete()
        args = listOf(
            "--release",
            "--min-api", "26",
            "--output", out.absolutePath,
            reflectiveLauncherJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}

// --- JetBrains Mono fonts as Compose-resource assets ----------------------------------------------
// Compose Multiplatform's resource→Android-assets packaging isn't wired for :ide-ui's AGP-9
// `com.android.kotlin.multiplatform.library` target: the generated `Res.font.*` accessors exist, but the
// bundled JetBrains Mono .ttf files (in :ide-ui/src/commonMain/composeResources/font) are never copied into
// the AAR/APK assets, so on device the loader can't find them and the editor falls back to the system
// monospace. Stage them into the app's assets at the exact path the Compose resource runtime reads —
// `composeResources/<resClass-package>/font/…` — so `Res.font.*` / rememberJetBrainsMono() resolve on device
// with no code change. (Desktop gets these via the JVM resources route and is unaffected.)
// NOTE: the `dev.ide.ui.generated.resources` segment must match :ide-ui's `packageOfResClass`.
val bundleComposeFontsAsset = tasks.register<Copy>("bundleComposeFontsAsset") {
    description = "Stage :ide-ui's JetBrains Mono compose-resource fonts into the APK assets (Android packaging gap)."
    from(project(":ide-ui").layout.projectDirectory.dir("src/commonMain/composeResources/font")) {
        include("*.ttf")
    }
    into(layout.buildDirectory.dir("compose-fonts-asset/composeResources/dev.ide.ui.generated.resources/font"))
}

// Same Android packaging gap as the fonts above, for the sample-game preview drawables (the store's Explore
// screenshots). PNGs are copied verbatim (unlike the strings, which are compiled), so stage them straight
// from :ide-ui's source composeResources at the path the Compose resource runtime reads on device —
// composeResources/<resClass-package>/drawable/… — so `Res.drawable.preview_*` resolves on device.
val bundleComposeDrawablesAsset = tasks.register<Copy>("bundleComposeDrawablesAsset") {
    description = "Stage :ide-ui's compose-resource drawables (sample previews) into the APK assets (Android packaging gap)."
    from(project(":ide-ui").layout.projectDirectory.dir("src/commonMain/composeResources/drawable")) {
        include("*.png")
    }
    into(layout.buildDirectory.dir("compose-drawables-asset/composeResources/dev.ide.ui.generated.resources/drawable"))
}

// --- AdMob ids (debug/profile = Google TEST ids; release = your real ids) ------------------------
// Debug + profile builds ALWAYS use Google's TEST ids: test ads are non-billable and safe to click during
// development, so there's no risk of an invalid-traffic ban. The release build uses the real ids when supplied
// via -PADMOB_APP_ID / -PADMOB_NATIVE_UNIT_ID (or the ADMOB_APP_ID / ADMOB_NATIVE_UNIT_ID env vars), falling
// back to the test ids so a fork builds fine with AdMob unconfigured. The App id reaches the manifest through
// the `admobAppId` placeholder; the native ad-unit id is a BuildConfig field AndroidAdHost reads. One native
// ad unit is reused across all four placements. OFFICIAL RELEASES MUST SET BOTH real ids.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdmobNativeUnitId = "ca-app-pub-3940256099942544/2247696110"
// The real ids are baked in as the release defaults (AdMob ids are not secret — they ship inside every APK),
// and stay overridable so a fork can point ads at its own AdMob account instead of the upstream one.
val realAdmobAppId = (findProperty("ADMOB_APP_ID") as String?) ?: System.getenv("ADMOB_APP_ID")
    ?: "ca-app-pub-7523005242346905~2985774451"
val realAdmobNativeUnitId = (findProperty("ADMOB_NATIVE_UNIT_ID") as String?) ?: System.getenv("ADMOB_NATIVE_UNIT_ID")
    ?: "ca-app-pub-7523005242346905/7440024785"

android {
    namespace = "dev.ide.android"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        // The Play Store identity (immutable once published). The Kotlin source package (namespace,
        // above) stays dev.ide.android — that's an internal build detail, independent of applicationId.
        applicationId = "com.tyron.code"
        // The real engine (project-model-impl/lang-jdt/...) leans on java.nio.file (Path/Files/walk),
        // which is API 26+. Targeting 26 keeps it native and avoids core-library desugaring complexity.
        minSdk = 26
        targetSdk = 36
        // versionCode must exceed the last published release (the previous-codebase app reached ~29).
        versionCode = 56
        versionName = "3.5.6"
        // connectedAndroidTest harness (the on-device Kotlin-compiler discovery spike).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Opt-in usage analytics (see docs/analytics.md). The Supabase project URL + *publishable* key are
        // baked in as BuildConfig fields, overridable per-build via -PANALYTICS_URL / -PANALYTICS_KEY or the
        // ANALYTICS_URL / ANALYTICS_KEY env vars (so the endpoint/key can rotate without a code change). The
        // publishable key is safe to ship: the `events` table's RLS allows INSERT only. An empty URL leaves
        // analytics wired but inert (the host falls back to the no-op service), so a fork can build with no
        // endpoint. Collection still never happens without the user's explicit consent.
        val analyticsUrl = (findProperty("ANALYTICS_URL") as String?) ?: System.getenv("ANALYTICS_URL")
            ?: "https://lqlpkeummmmglikumotx.supabase.co"
        val analyticsKey = (findProperty("ANALYTICS_KEY") as String?) ?: System.getenv("ANALYTICS_KEY")
            ?: "sb_publishable_5T14bUAG6fOGz47kwYzG7A_25dj3ap4"
        buildConfigField("String", "ANALYTICS_URL", "\"$analyticsUrl\"")
        buildConfigField("String", "ANALYTICS_KEY", "\"$analyticsKey\"")

        // AdMob defaults = Google TEST ids. Debug inherits these as-is; `release` overrides to the real ids
        // below, and `profile` (a local perf build) is forced back to test. The App id reaches the manifest
        // via ${admobAppId}; the native ad-unit id is read from BuildConfig by AndroidAdHost.
        manifestPlaceholders["admobAppId"] = testAdmobAppId
        buildConfigField("String", "AD_NATIVE_UNIT_ID", "\"$testAdmobNativeUnitId\"")
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    // Stage the generated assets (kotlin-stdlib.jar, kotlinc-resources.zip) into the merged assets so the
    // on-device compiler can load them. AGP 9 disallows a Provider here, so register the static dirs the
    // tasks write to; ordering is carried by the `preBuild.dependsOn(...)` below (same pattern as aapt2).
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("kotlin-stdlib-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("kotlinc-resources-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-runtime-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-fonts-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-strings-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-drawables-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("r8-dex-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("reflective-launcher-asset").get().asFile)

    // Release signing, never committed. Resolution order per field: keystore.properties (gitignored,
    // alongside this build script) → Gradle property (-PRELEASE_*) → env var (RELEASE_*). With no keystore
    // the release build is left unsigned — fine for Play, which re-signs with the managed app key (you
    // upload an AAB signed with your upload key). See keystore.properties.example.
    signingConfigs {
        val keystoreProps = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        // storeFile is resolved relative to the repo root so a relative path in keystore.properties works
        // regardless of where Gradle is invoked from.
        fun signingValue(key: String, prop: String, env: String): String? =
            keystoreProps.getProperty(key) ?: (findProperty(prop) as String?) ?: System.getenv(env)

        val storePath = signingValue("storeFile", "RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
        val storeFileResolved = storePath?.let { rootProject.file(it) }
        if (storeFileResolved != null && storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 is OFF: the app loads JDT/ecj/D8/apksig classes reflectively and dexes user code at
            // runtime, so aggressive shrinking would strip needed classes. Revisit with keep rules if
            // download size becomes a concern.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // The shipped build serves real AdMob ads (falls back to test ids if none were configured).
            manifestPlaceholders["admobAppId"] = realAdmobAppId
            buildConfigField("String", "AD_NATIVE_UNIT_ID", "\"$realAdmobNativeUnitId\"")
        }
        // A release-like, non-debuggable build that's still installable locally (signed with the debug key).
        // Use this — never `debug` — to judge runtime/typing/recomposition performance: a `debuggable` app
        // runs with ART optimizations off and the Compose runtime is disproportionately slow in that mode,
        // so debug timings are not representative. Mirrors `release` (R8 stays off for the reflection/runtime-
        // dexing reasons above); only the signing differs so `adb install` works without the release keystore.
        create("profile") {
            initWith(getByName("release"))
            // Sign with the release/upload key when a keystore is configured (so testers get a build with
            // the published signature identity); fall back to the debug key so the variant still installs
            // locally when no release keystore is present.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // This build is for on-device perf testing, so keep TEST ads (initWith(release) copied the real
            // ids — undo that) — a tester must never click a live ad.
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "AD_NATIVE_UNIT_ID", "\"$testAdmobNativeUnitId\"")
        }
    }

    // AGP's built-in Kotlin aligns its jvmTarget to these Java options.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Temporarily enabled: backports newer java.* APIs (java.time, java.nio.file.Files.readString, …)
        // below their native API level via desugar_jdk_libs (see the coreLibraryDesugaring dep below).
        isCoreLibraryDesugaringEnabled = true
    }

    // The Eclipse/OSGi jars (jdt.core, ecj, core.runtime, equinox.*, osgi, …) ship lots of overlapping
    // bundle metadata (plugin.xml, OSGI-INF, *.profile, signatures, …). None of it is used — we run the
    // JDT compiler/DOM standalone, not inside an OSGi container — so drop it all and let the resource
    // merger pass. (The *classes* are kept; only these non-class resources are pruned.)
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA",
                "META-INF/eclipse.inf", "META-INF/ECLIPSE_.*",
                "plugin.xml", "plugin.properties", "fragment.xml", "fragment.properties",
                "about.html", "about.ini", "about.properties", "about.mappings", "about_files/**",
                "systembundle.properties", "profile.list", "**/*.profile", ".api_description",
                "**/*.api_description", ".options", "OSGI-INF/**", "OSGI-OPT/**",
                "*.html", "modeling32.png", "eclipse32.png", "eclipse32.gif", "eclipse_lg.png",
                "notice.html", "epl-v10.html", "license.html",
                // dual-license / notice files the Eclipse jars carry
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES", "META-INF/*.txt",
            )
            pickFirsts += setOf(
                "META-INF/MANIFEST.MF",
                // The compiler jars carry serialized builtins that can collide with the real kotlin-stdlib
                // on the runtime classpath. Same content — keep one. (These are loaded at runtime by Kotlin
                // reflection/builtins, so pickFirst, not exclude.)
                "**/*.kotlin_builtins",
                "**/*.kotlin_metadata",
            )
        }
        // The bundled aapt2 is a statically-linked Android executable shipped as libaapt2.so. Force the
        // legacy packaging that EXTRACTS it into nativeLibraryDir at install (the only dir ART lets you
        // exec from), and keep it un-stripped — it is not an ordinary shared object, so AGP's strip
        // (when an NDK is present) would corrupt it.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libaapt2.so")
        }
    }
}

// --- javax.xml.stream (StAX API) for on-device K2 ------------------------------------------------
// IntelliJ-core parses its plugin/extension descriptors with StAX. The implementation (aalto + stax2,
// relocated) is bundled and dexed with the compiler, but the StAX *API* (javax.xml.stream) is a JDK
// module Android omits entirely (Android ships SAX/DOM/XmlPullParser, not StAX) — so the dexed aalto
// classes fail to resolve javax.xml.stream.XMLStreamReader at runtime. App classes may live in javax.*
// (unlike java.*), so we extract javax/xml/stream/** from the build JBR's java.xml module and dex it,
// exactly as libs/java-compiler.jar supplies the also-absent javax.lang.model. Version-matched to the
// build JDK, so it agrees with what aalto expects. (javax.xml.namespace/.transform it references DO exist
// on the device platform, so we ship only the missing stream subpackage.)
val generateStaxApiJar = tasks.register("generateStaxApiJar") {
    description = "Extract javax.xml.stream (StAX API) from the build JDK's java.xml module into a dexable jar."
    val outJar = layout.buildDirectory.file("stax-api/stax-api.jar")
    outputs.file(outJar)
    doLast {
        val out = outJar.get().asFile
        out.parentFile.mkdirs()
        // The running build JVM (JBR 17) exposes its modules through the built-in jrt filesystem.
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        val streamRoot = jrt.getPath("/modules/java.xml/javax/xml/stream")
        var count = 0
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            Files.walk(streamRoot).use { paths ->
                paths.filter { it.toString().endsWith(".class") }.forEach { p ->
                    val entryName = p.toString().removePrefix("/modules/java.xml/")
                    zos.putNextEntry(ZipEntry(entryName).apply { time = 315532800000L })
                    Files.copy(p, zos)
                    zos.closeEntry()
                    count++
                }
            }
        }
        logger.lifecycle("generateStaxApiJar: wrote $count javax.xml.stream class(es) → ${out.name}")
    }
}

// --- javax.* JDK types (java.desktop, java.management) for on-device K2 --------------------------
// IntelliJ-core's PSI carries a Swing-based icon API: dozens of classes (ElementBase, PsiPackageBase,
// the asJava light classes, …) declare methods returning javax.swing.Icon, and four marker interfaces
// (ui.icons.ReplaceableIcon/CompositeIcon, openapi.util.ScalableIcon/DummyIcon) `extends javax.swing.Icon`.
// javax.swing is a JDK (java.desktop) package Android omits entirely. On a strict ART verifier this is
// fatal at *class load*: KotlinCoreEnvironment.createForProduction → KotlinJavaPsiFacade.<clinit> builds a
// PsiPackageImpl, whose ElementBase/PsiPackageBase supertypes fail to verify ("can't resolve returned type
// javax.swing.Icon"), which kills the Kotlin parse host AND the bundled K2 compiler.
// App classes may live in javax.* (unlike java.*), so we dex the real javax.swing.Icon interface from the
// build JBR's java.desktop module — exactly like generateStaxApiJar / libs/java-compiler.jar. It is a pure
// interface (3 abstract methods); the java.awt.Component/Graphics it names live only in those abstract
// descriptors (never resolved at load, never invoked headless), so Icon alone suffices and pulls in no AWT.
// With the type present, RowIcon stays a subtype of Icon and every icon method/ctor/<clinit> + the four
// markers verify normally — no bytecode surgery (this replaces the old ASM SwingIconArtPass interface strip,
// which only made the markers load and then broke verification of RowIcon-returning methods).
//
// javax.management (java.management module) gets the same treatment for the platform's low-memory watcher:
// AppScheduledExecutorService.<init> (reached by KaFirSessionProvider — the K2 Analysis API session, device
// logcat confirmed) constructs LowMemoryWatcherManager, whose <init> stores a NotificationListener-typed
// field implemented by the anonymous LowMemoryWatcherManager$3 — so the $3 CLASS LINK and the field write
// need the interface present, or every K2 analyze/complete dies with NoClassDefFoundError. Every method
// that actually CALLS JMX there ($2.run subscribing via ManagementFactory, shutdown, getMajorGcTime) also
// touches java.lang.management and is already gutted by the kotlinc-art ManagementStubPass, so the shipped
// types are load-time surface only: NotificationListener + NotificationFilter (pure interfaces over
// java.util.EventListener/Serializable) and Notification (a plain EventObject subclass). NotificationEmitter
// is deliberately NOT shipped — it appears only inside gutted bodies, and it would drag in the
// NotificationBroadcaster → MBeanNotificationInfo chain.
// module → class-file path, extracted from the build JBR's jrt image below.
val javaxApiEntries = listOf(
    "java.desktop" to "javax/swing/Icon.class",
    "java.management" to "javax/management/NotificationListener.class",
    "java.management" to "javax/management/NotificationFilter.class",
    "java.management" to "javax/management/Notification.class",
)
val generateSwingApiJar = tasks.register("generateSwingApiJar") {
    description = "Extract the javax.swing/javax.management types the unshaded platform links against into a dexable jar."
    val outJar = layout.buildDirectory.file("swing-api/swing-api.jar")
    // The entry list is the task's real input; without it Gradle treats an existing jar as up-to-date
    // forever and a newly added type never ships.
    inputs.property("entries", javaxApiEntries.map { "${it.first}:${it.second}" })
    outputs.file(outJar)
    doLast {
        val out = outJar.get().asFile
        out.parentFile.mkdirs()
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            for ((module, path) in javaxApiEntries) {
                zos.putNextEntry(ZipEntry(path).apply { time = 315532800000L })
                Files.copy(jrt.getPath("/modules/$module/$path"), zos)
                zos.closeEntry()
            }
        }
        logger.lifecycle("generateSwingApiJar: wrote ${javaxApiEntries.size} javax classes → ${out.name}")
    }
}

// --- ART shims for the unshaded IntelliJ platform (javax.swing.SwingUtilities, jdk.jfr.*) ---------
// The unshaded platform (:kotlin-compiler-deps) touches two more JDK packages Android omits but that app
// classes MAY define (unlike java.*): javax.swing.SwingUtilities (MockApplication.invokeLater / EDT checks,
// hit the moment JavaCoreApplicationEnvironment registers the ClassFileDecompilers EP listener - device
// logcat confirmed) and jdk.jfr (the platform's diagnostic JFR event classes). Compile the headless shim
// sources (src/artShims, inherited from the retired aa-runtime module) against android.jar (so javax.swing
// doesn't exist at compile time) and dex them. The old aaShims' com.intellij.* replacements are gone: those
// FQNs live in the merged compiler jar and are handled by the kotlinc-art ASM passes instead.
val artShimAndroidJar = provider {
    val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.let { Properties().apply { it.inputStream().use { s -> load(s) } }.getProperty("sdk.dir") }
        ?: throw GradleException("Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)")
    File(sdkDir, "platforms/android-36/android.jar")
}
val compileArtShims = tasks.register<JavaCompile>("compileArtShims") {
    source = fileTree(layout.projectDirectory.dir("src/artShims/java"))
    classpath = files()
    options.bootstrapClasspath = files(artShimAndroidJar)
    sourceCompatibility = "8"
    targetCompatibility = "8"
    destinationDirectory.set(layout.buildDirectory.dir("art-shims/classes"))
}
val artShimsJar = tasks.register<Jar>("artShimsJar") {
    from(compileArtShims.flatMap { it.destinationDirectory })
    archiveFileName.set("art-shims.jar")
    destinationDirectory.set(layout.buildDirectory.dir("art-shims"))
}

// --- on-device native aapt2 ----------------------------------------------------------------------
// ART can only exec binaries from nativeLibraryDir and Google ships no Android-ABI aapt2, so we bundle a
// prebuilt aapt2 as libaapt2.so per ABI. AGP packages it; the installer extracts it where it can run.
//
// Source = ReVanced/aapt2 (https://github.com/ReVanced/aapt2): a current, on-device-targeted build whose
// LOAD segments are 16 KB-aligned (verified `p_align == 0x4000`). This matters: a binary with 4 KB-aligned
// segments — e.g. the lzhiyong android-sdk-tools build we shipped before — cannot be mapped on a 16 KB-page
// device and the kernel kills it with SIGSEGV the instant it execs (even `aapt2 version` crashes). A
// 16 KB-aligned binary loads on both 4 KB and 16 KB pages, so this works across devices. ReVanced ships
// aapt2 only; zipalign is not needed (ApksigSigner aligns the APK in-process via apksig), so we no longer
// bundle it. Bump [aapt2Source] to force a re-download when changing the binary; offline once populated.
val aapt2ReleaseTag = "v1.1.0"
val aapt2Source = "revanced-$aapt2ReleaseTag" // identity written next to the binary; change → re-fetch
val aapt2Abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") // == ReVanced asset suffixes

val fetchAndroidBuildTools = tasks.register("fetchAndroidBuildTools") {
    description = "Download the ReVanced aapt2 prebuilt into src/main/jniLibs/<abi>/libaapt2.so."
    group = "build setup"
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val source = aapt2Source
    val tag = aapt2ReleaseTag
    val abis = aapt2Abis
    doLast {
        for (abi in abis) {
            val abiDir = File(jniLibsDir, abi)
            val aapt2 = File(abiDir, "libaapt2.so")
            val marker = File(abiDir, ".aapt2-source")
            val upToDate = aapt2.exists() && aapt2.length() > 0L &&
                marker.takeIf { it.exists() }?.readText()?.trim() == source
            if (upToDate) continue // already the right binary → offline
            abiDir.mkdirs()
            // Drop any binary from a previous source — notably the non-working lzhiyong aapt2/zipalign,
            // which would otherwise still be packaged and exec'd (and SIGSEGV) on a 16 KB device.
            File(abiDir, "libaapt2.so").delete()
            File(abiDir, "libzipalign.so").delete()
            val url = "https://github.com/ReVanced/aapt2/releases/download/$tag/aapt2-$abi"
            logger.lifecycle("Fetching aapt2 ($abi) from $url")
            val tmp = File.createTempFile("aapt2-$abi", ".bin")
            try {
                URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                // Guard against a GitHub error/HTML page silently landing as the binary.
                val magic = tmp.inputStream().use { it.readNBytes(4) }
                val isElf = magic.size == 4 && magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
                if (!isElf || tmp.length() < 100_000L) {
                    throw GradleException("Downloaded aapt2 ($abi) is not an ELF binary (${tmp.length()} bytes from $url) — release layout changed?")
                }
                tmp.copyTo(aapt2, overwrite = true)
                aapt2.setExecutable(true)
                marker.writeText(source)
            } finally {
                tmp.delete()
            }
        }
    }
}

// Run before anything AGP does, so the freshly-fetched lib*.so are on disk when the native-lib merge runs,
// and the staged kotlin-stdlib.jar asset is present when the asset merge runs.
tasks.named("preBuild").configure {
    dependsOn(fetchAndroidBuildTools, bundleKotlinStdlibAsset, bundleKotlincResourcesAsset, bundleComposeRuntimeAsset, bundleComposeFontsAsset, bundleComposeStringAsset, bundleComposeDrawablesAsset, bundleR8DexAsset, bundleReflectiveLauncherDex)
}

// Same Android packaging gap as the fonts above, for the i18n string resources. :ide-ui's
// values/strings.xml is compiled by the Compose resources plugin into binary `.cvr` files (unlike fonts,
// which are copied verbatim), so we stage the *processed* output, not the source. The compiled strings are
// platform-independent, so the desktop target's processedResources is a reliable source; depend on the
// task that produces them (desktopProcessResources) rather than the whole :ide-ui build. Staged at the
// exact path the Compose resource runtime reads on device — composeResources/<resClass-package>/values*/.
val bundleComposeStringAsset = tasks.register<Copy>("bundleComposeStringAsset") {
    description = "Stage :ide-ui's i18n compose-resource strings into the APK assets (Android packaging gap)."
    dependsOn(":ide-ui:desktopProcessResources")
    from(project(":ide-ui").layout.buildDirectory.dir("processedResources/desktop/main/composeResources/dev.ide.ui.generated.resources")) {
        include("values*/**/*.cvr")
    }
    into(layout.buildDirectory.dir("compose-strings-asset/composeResources/dev.ide.ui.generated.resources"))
}

// The stock Eclipse jars we relocate for ART (ecj, core.runtime, equinox.common) reach the app's runtime
// classpath through several project dependencies: :ide-core (excluded inline below), but also :android-support
// and :layout-preview-impl, which depend on :lang-jdt without that exclude. Dexing both the stock jar and its
// ART-relocated copy is a duplicate-class (checkDuplicateClasses) and duplicate-resource (mergeJavaResource)
// failure, so strip the three stock modules from every runtime classpath; only the relocated files(...) copies
// added below get dexed. Scoped to *RuntimeClasspath so (a) the resolvable helper configs that FEED the
// relocate tasks (ecjUnpatched, eclipseRuntimeUnpatched) keep the stock jars they consume as input, and (b) the
// androidTest compile classpath still resolves lang-jdt's ecj/runtime types when compiling the on-device spike.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.eclipse.jdt", module = "ecj")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.core.runtime")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.equinox.common")
        // JNA arrives TWICE under different group ids: the standard net.java.dev.jna 5.15 (via jdt.core ->
        // eclipse core.filesystem) and JetBrains' org.jetbrains.intellij.deps.jna 5.9 fork (via
        // :kotlin-compiler-deps' support set for the unshaded IntelliJ platform). Same com.sun.jna classes,
        // so D8 rejects the pair as duplicates. Keep the standard (newer) one that was always dexed; the
        // fork's classes are API-compatible for the platform's (rarely hit) JNA touchpoints.
        exclude(group = "org.jetbrains.intellij.deps.jna", module = "jna")
        exclude(group = "org.jetbrains.intellij.deps.jna", module = "jna-platform")
    }
    // Force guava's JRE flavor over its Android flavor. The `implementation(libs.guava)` edge below requests
    // `org.gradle.jvm.environment = standard-jvm`, which makes guava's `jreRuntimeElements` a candidate
    // alongside the `androidRuntimeElements` the transitive (bundletool) edges pull in this Android app; both
    // provide the `com.google.guava:guava` capability, so they conflict. Resolve that conflict to the JRE
    // variant: its `com.google.common.base.Predicate` extends `java.util.function.Predicate` (the Android
    // flavor's does not), which the dexed bundletool relies on when it filters streams with guava predicates
    // — otherwise ART throws `IncompatibleClassChangeError` building an .aab. See the guava dependency below.
    resolutionStrategy.capabilitiesResolution.withCapability("com.google.guava:guava") {
        candidates.firstOrNull { "jre" in it.variantName }?.let { jre ->
            select(jre)
            because("guava JRE flavor: Predicate extends java.util.function.Predicate (dexed bundletool needs it on ART)")
        }
    }
}

dependencies {
    implementation(project(":ide-ui"))

    // The real on-device IDE engine, shared with :ide-desktop. ide-core pulls in lang-jdt (jdt.core +
    // ecj) transitively along with the Eclipse platform runtime jars (org.eclipse.core.runtime, etc.).
    // Those must be kept: even JDT's standalone ASTParser/completion path references base runtime types
    // (org.eclipse.core.runtime.Plugin/IStatus/…) at class-load time, so excluding them is a runtime
    // NoClassDefFoundError. They dex fine; the duplicate Eclipse metadata is handled by `packaging` below.
    //
    // ecj is the one exception: drop the stock jar here and dex the ART-relocated copy instead (it is
    // disjoint from jdt.core, so removing it only excludes the compiler classes, which we add back
    // patched). See the `relocateEcjForArt` task above.
    implementation(project(":ide-core")) {
        exclude(group = "org.eclipse.jdt", module = "ecj")
        // Drop the stock core.runtime / equinox.common: the StackWalker-relocated copies are added back
        // below (see relocateCoreRuntimeForArt / relocateEquinoxCommonForArt). This handles the :ide-core
        // path; the runtime-classpath exclude above catches the same jars arriving via :android-support and
        // :layout-preview-impl.
        exclude(group = "org.eclipse.platform", module = "org.eclipse.core.runtime")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.equinox.common")
    }
    // Excluding core.runtime above also drops org.eclipse.core.contenttype (it reaches the graph only
    // through core.runtime). JDT's public DOM / JavaCore.getOptions() path references IContentTypeManager
    // at class-load time, so without it analysis throws NoClassDefFoundError on-device. It is a plain jar
    // with no ART-absent / StackWalker references, so dex it as-is (no relocation needed). isTransitive =
    // false: take ONLY the contenttype jar — its transitive equinox.common/core.runtime are the unpatched
    // (StackWalker-bearing) jars we relocate-and-readd above, and the rest of its graph (osgi, registry)
    // is already present via :ide-core's core.resources.
    implementation(libs.eclipse.contenttype) { isTransitive = false }
    // Same story for org.eclipse.core.jobs: excluding core.runtime drops it, but the public DOM ASTParser
    // constructor (ASTParser.<init> -> DefaultWorkingCopyOwner.PRIMARY) reaches org.eclipse.core.runtime.jobs.
    // ISchedulingRule through the working-copy/IJavaElement hierarchy at class-load time. ART links that
    // hierarchy eagerly, so without core.jobs it is an uncatchable NoClassDefFoundError that disables editor
    // analysis + source indexing (JavaSourceIndexer). Plain jar, no ART-absent references, so dex as-is.
    // isTransitive = false: take only the jobs jar; its core.runtime/equinox.common deps are the relocated
    // copies added above, and osgi is already present via core.resources.
    implementation(libs.eclipse.jobs) { isTransitive = false }
    // Layout-preview live custom-view runtime: the Bridge classes + DexClassLoader factory live here and
    // need the contracts (api), the CustomViewRuntime/StyledAttrResolver seam (impl), and D8InProcessDexer.
    implementation(project(":layout-preview-api"))
    implementation(project(":layout-preview-impl"))
    implementation(project(":android-support"))
    // Opt-in usage analytics engine: DefaultAnalyticsService + the Supabase sink + the crash reporter. The
    // analytics-api types reach here transitively via :ide-core (which exposes them as `api`).
    implementation(project(":analytics-impl"))
    // The logging facade (Log) — used directly here for the main-thread guard + the analytics log sink. It
    // reaches :ide-core only as `implementation` (not transitive), so depend on it explicitly.
    implementation(project(":platform-core"))
    implementation(files(relocateEcjForArt.flatMap { it.outputJar }))
    // The StackWalker-relocated Eclipse runtime jars (replacing the stock ones excluded from :ide-core above).
    implementation(files(relocateCoreRuntimeForArt.flatMap { it.outputJar }))
    implementation(files(relocateEquinoxCommonForArt.flatMap { it.outputJar }))

    // The JDK `java.compiler` module's javax.* classes (javax.lang.model / .tools / .annotation.processing),
    // extracted from the JBR. ecj's DOM ASTParser + batch compiler reference these at class-load time
    // (e.g. javax.lang.model.SourceVersion), and they exist neither on ART nor in android.jar — so we dex
    // them into the app. Without this, analysis/diagnostics throw NoClassDefFoundError on-device.
    implementation(files("libs/java-compiler.jar"))

    // javax.xml.stream (StAX API) — absent on Android; needed by the dexed aalto/stax2 the K2 compiler uses
    // to parse its plugin descriptors. Generated from the build JDK above (see generateStaxApiJar).
    implementation(files(generateStaxApiJar.map { it.outputs.files.singleFile }))

    // javax.swing.Icon (java.desktop) — absent on Android; IntelliJ-core's PSI icon API names it in method
    // signatures and four marker interfaces, so without it the dexed K2 compiler + parse host fail ART
    // verification at class load. Generated from the build JDK above (see generateSwingApiJar).
    implementation(files(generateSwingApiJar.map { it.outputs.files.singleFile }))

    // javax.swing.SwingUtilities + jdk.jfr.* headless shims (see compileArtShims above): the unshaded
    // IntelliJ platform reaches them at runtime (MockApplication.invokeLater, JFR diagnostics) and Android
    // omits both packages.
    implementation(files(artShimsJar.flatMap { it.archiveFile }))

    // (gnu.trove and the platform support libs now arrive transitively via :kotlin-compiler-deps, the
    // unshaded compiler dependency set that :lang-kotlin api-consumes.)

    // The Jetpack Compose kotlinc plugin's classes — dexed into the app so kotlinc can resolve its
    // `ComposePluginRegistrar` on ART. The build feeds the plugin to the in-process K2JVMCompiler via
    // `-Xplugin` (the jar is the lang-kotlin bundled resource); kotlinc reads the service descriptor from
    // that jar but defines the registrar class through parent delegation to the app classloader (a jar's
    // bytecode can't be loaded at runtime on ART), so the class must live here. Non-transitive: it needs
    // only its own classes — the (unshaded) Kotlin compiler it builds on is already dexed via :lang-kotlin.
    implementation(libs.kotlin.compose.compiler.plugin.ide) { isTransitive = false }

    // build-engine's DexRunner/DexBackend ports (kept `implementation` in :ide-core, so not transitive):
    // :ide-android supplies the on-device DexClassLoader runner that backs the Java `run` on ART.
    implementation(project(":build-engine"))

    // On-device build tools, statically linked + run IN-PROCESS (ART has no `java -jar` to fork): D8/R8
    // (the dexer/shrinker) and apksig (APK v1/v2/v3 signing). android-support keeps these compileOnly+test;
    // the device app is the one place they're actually bundled and dexed. The native aapt2/zipalign are not
    // here — they ship as jniLibs prebuilts (see fetchAndroidBuildTools below).
    implementation(libs.android.r8)
    implementation(libs.android.apksig)
    // Bouncy Castle: in-process keystore creation on device (keypair + self-signed cert → PKCS12; no keytool).
    implementation(libs.bouncycastle.pkix)
    // bundletool builds the .aab in-process on device (BundletoolInProcess). Pure Java, so it dexes into the
    // app like d8/apksig; its closure (guava/protobuf/dagger) comes transitively. android-support keeps it
    // compileOnly+test. NOTE: dexing bundletool's closure into the app is new ground — if assembleDebug hits
    // a duplicate-class / mergeJavaResource clash, add the offending entry to the packaging{} block above.
    implementation(libs.android.bundletool)

    // Force the JRE flavor of guava (not the Android flavor). In an Android application the runtime
    // classpath requests `org.gradle.jvm.environment = android`, so guava's Gradle module metadata
    // resolves its coordinate (e.g. `33.2.0-jre`) to the `androidRuntimeElements` variant, which is
    // `available-at` the `guava-*-android.jar`. That Android flavor's `com.google.common.base.Predicate`
    // does NOT extend `java.util.function.Predicate` (it targets pre-24 Android), whereas the JRE flavor's
    // does. bundletool is compiled against the JRE flavor and passes guava `Predicate`s into
    // `java.util.stream.Stream.filter(java.util.function.Predicate)`; with the Android flavor dexed in, ART
    // throws `IncompatibleClassChangeError` ("Predicates$NotPredicate does not implement
    // java.util.function.Predicate") when a user builds an .aab. minSdk is 26, so `java.util.function.*`
    // is native and the JRE flavor runs fine — pin the environment attribute to standard-jvm so the JRE
    // jar is the one dexed.
    implementation(libs.guava) {
        attributes {
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
            )
        }
    }

    // Core-library desugaring runtime (temporarily enabled — see isCoreLibraryDesugaringEnabled above).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    // AdMob native ads (Android launcher only), rendered through the AdHost seam. Excludes protobuf-lite: this
    // app already dexes full protobuf (via :android-support's bundletool), and the two share the com.google.
    // protobuf.* package, so keeping both is a D8 duplicate-class failure. The ads SDK's protobuf touchpoints
    // are API-compatible with the full runtime already present.
    implementation(libs.play.services.ads) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    // FileProvider (androidx.core.content.FileProvider) — hands other apps content:// URIs to our
    // app-private project files for Share / "Open with", and grants read access on inbound intents.
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.core)

    // The on-device Kotlin interpreter (:interp-core) + its Compose bridge/render surface (:interp-compose,
    // dev.ide.interp.compose — KMP, re-exporting :interp-core): drives a ResolvedTree against the real
    // Compose runtime so the editor's @Preview renders live (docs/compose-interpreter.md, step 4).
    implementation(project(":interp-core"))
    implementation(project(":interp-compose"))

    // On-device instrumentation: the Kotlin-compiler-on-ART discovery spike.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // The compiler API (K2JVMCompiler/K2JVMCompilerArguments/MessageCollector/…) to COMPILE the spike
    // against. It arrives in the app only as a transitive `implementation` (via :ide-core → :lang-kotlin),
    // which doesn't leak to the androidTest *compile* classpath — and at runtime the app's dexed copy
    // already provides it — so compileOnly is exactly right: types to compile, no second dexed copy.
    androidTestCompileOnly(project(":kotlin-compiler-deps"))
    // The spike's Compose case references ComposeCompilerPlugin (lang-kotlin) to locate the bundled plugin
    // jar. Like the compiler API, lang-kotlin reaches the app only transitively, so add it compileOnly: the
    // type to compile against, with the app's dexed copy providing it at runtime.
    androidTestCompileOnly(project(":lang-kotlin"))
    // The Java-17-on-ART build spike calls JdtBatchCompiler (lang-jdt). Like the above, lang-jdt reaches the
    // app only transitively (via :ide-core), so add it compileOnly — the app's dexed copy provides it (and the
    // relocated ecj-art) at runtime.
    androidTestCompileOnly(project(":lang-jdt"))
    // The on-device build benchmark (OnDeviceBuildBenchmarkTest) opens a project model + drives AndroidBuildSystem
    // directly, and (self-contained mode) seeds a Material project + resolves it with the Maven resolver. These
    // reach the app only transitively via :ide-core, so compile against them here; the app's dexed copies provide
    // them at runtime.
    androidTestCompileOnly(project(":project-model-impl"))
    androidTestCompileOnly(project(":deps-impl"))
    androidTestCompileOnly(project(":deps-api"))
    // JavaPsiConcurrentArtSpikeTest drives the IntelliJ-PSI Java indexer (JavaSourceIndexer) + the shared PSI
    // host's concurrent read path (IntellijPsiHost.parseConcurrent) on ART. Both reach the app only
    // transitively via :ide-core, so compile against them here; the app's dexed copies provide them at runtime.
    androidTestCompileOnly(project(":lang-java"))
    androidTestCompileOnly(project(":intellij-psi-host"))
}