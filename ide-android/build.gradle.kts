// Android launcher: a Compose Android application that renders the reusable IDE UI (:ide-ui) — the same
// commonMain composables the desktop launcher uses — over an Android [AndroidIdeBackend]. It is the
// Android counterpart to :ide-desktop. Under AGP 9, Kotlin is built into `com.android.application` (no
// kotlin-android plugin); Compose comes from the Compose Multiplatform + Compose-compiler plugins.
import dev.ide.build.RelocateTypesInJar
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
// APK exposes those only as classloader resources, not files. So we ship the compiler's resources (the jar
// MINUS its already-dexed .class entries — small) as an asset; the app extracts it to a dir at runtime and
// publishes the path in the `kotlinc.art.home` system property, which the ASM PathUtil pass reads
// (see buildSrc/.../PathUtilSelfLocatePass).
val kotlincCompilerArtifact: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { kotlincCompilerArtifact(libs.kotlin.compiler.embeddable) { isTransitive = false } }

val bundleKotlincResourcesAsset = tasks.register("bundleKotlincResourcesAsset") {
    description = "Strip .class entries from kotlin-compiler-embeddable into a kotlinc-resources.zip asset."
    val jarFiles = kotlincCompilerArtifact
    val outZip = layout.buildDirectory.file("kotlinc-resources-asset/kotlinc-resources.zip")
    inputs.files(jarFiles)
    outputs.file(outZip)
    doLast {
        val jar = jarFiles.singleFile
        val out = outZip.get().asFile
        out.parentFile.mkdirs()
        var kept = 0
        ZipFile(jar).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (e in zip.entries()) {
                    if (e.isDirectory || e.name.endsWith(".class")) continue
                    zos.putNextEntry(ZipEntry(e.name).apply { time = 315532800000L }) // fixed → reproducible
                    zip.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                    kept++
                }
            }
        }
        logger.lifecycle("bundleKotlincResourcesAsset: kept $kept non-class resource(s) → ${out.name}")
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
        versionCode = 36
        versionName = "3.0.5"
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
    }

    buildFeatures {
        // VERSION_NAME / VERSION_CODE + the ANALYTICS_* fields above are read from BuildConfig at runtime.
        buildConfig = true
    }

    // Stage the generated assets (kotlin-stdlib.jar, kotlinc-resources.zip) into the merged assets so the
    // on-device compiler can load them. AGP 9 disallows a Provider here, so register the static dirs the
    // tasks write to; ordering is carried by the `preBuild.dependsOn(...)` below (same pattern as aapt2).
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("kotlin-stdlib-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("kotlinc-resources-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-runtime-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-fonts-asset").get().asFile)

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
        }
        // A release-like, non-debuggable build that's still installable locally (signed with the debug key).
        // Use this — never `debug` — to judge runtime/typing/recomposition performance: a `debuggable` app
        // runs with ART optimizations off and the Compose runtime is disproportionately slow in that mode,
        // so debug timings are not representative. Mirrors `release` (R8 stays off for the reflection/runtime-
        // dexing reasons above); only the signing differs so `adb install` works without the release keystore.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
                // kotlin-compiler-embeddable bundles a shaded stdlib, so its .kotlin_builtins / .kotlin_metadata
                // collide with the real kotlin-stdlib on the runtime classpath. Same 2.4.0 content — keep one.
                // (These are loaded at runtime by Kotlin reflection/builtins, so pickFirst, not exclude.)
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

// --- on-device native aapt2 ----------------------------------------------------------------------
// ART can only exec binaries from nativeLibraryDir and Google ships no Android-ABI aapt2, so we bundle a
// prebuilt aapt2 as libaapt2.so per ABI. AGP packages it; the installer extracts it where it can run.
//
// Source = ReVanced/aapt2 (https://github.com/ReVanced/aapt2): a current, on-device-targeted build whose
// LOAD segments are 16 KB-aligned (verified `p_align == 0x4000`). This matters: a binary with 4 KB-aligned
// segments — e.g. the lzhiyong android-sdk-tools build we shipped before — cannot be mapped on a 16 KB-page
// device and the kernel kills it with SIGSEGV the instant it execs (even `aapt2 version` crashes). A
// 16 KB-aligned binary loads on both 4 KB and 16 KB pages, so this works across devices. ReVanced ships
// aapt2 only; zipalign is optional (ApksigSigner signs unaligned when it is absent), so we no longer bundle
// it. Bump [aapt2Source] to force a re-download when changing the binary; offline once populated.
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
    dependsOn(fetchAndroidBuildTools, bundleKotlinStdlibAsset, bundleKotlincResourcesAsset, bundleComposeRuntimeAsset, bundleComposeFontsAsset)
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
    }
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

    // The JDK `java.compiler` module's javax.* classes (javax.lang.model / .tools / .annotation.processing),
    // extracted from the JBR. ecj's DOM ASTParser + batch compiler reference these at class-load time
    // (e.g. javax.lang.model.SourceVersion), and they exist neither on ART nor in android.jar — so we dex
    // them into the app. Without this, analysis/diagnostics throw NoClassDefFoundError on-device.
    implementation(files("libs/java-compiler.jar"))

    // javax.xml.stream (StAX API) — absent on Android; needed by the dexed aalto/stax2 the K2 compiler uses
    // to parse its plugin descriptors. Generated from the build JDK above (see generateStaxApiJar).
    implementation(files(generateStaxApiJar.map { it.outputs.files.singleFile }))

    // gnu.trove.* — IntelliJ-core uses Trove4j (un-relocated) but kotlin-compiler-embeddable doesn't bundle
    // it; dex it so the on-device compiler's FileUtil/VFS classes resolve (matches CodeAssist's approach).
    implementation(libs.trove4j)

    // The Jetpack Compose kotlinc plugin's classes — dexed into the app so kotlinc can resolve its
    // `ComposePluginRegistrar` on ART. The build feeds the plugin to the in-process K2JVMCompiler via
    // `-Xplugin` (the jar is the lang-kotlin bundled resource); kotlinc reads the service descriptor from
    // that jar but defines the registrar class through parent delegation to the app classloader (a jar's
    // bytecode can't be loaded at runtime on ART), so the class must live here. Non-transitive: it needs
    // only its own classes — the (embeddable) Kotlin compiler it builds on is already dexed via :lang-kotlin.
    implementation(libs.kotlin.compose.compiler.plugin) { isTransitive = false }

    // build-engine's DexRunner/DexBackend ports (kept `implementation` in :ide-core, so not transitive):
    // :ide-android supplies the on-device DexClassLoader runner that backs the Java `run` on ART.
    implementation(project(":build-engine"))

    // On-device build tools, statically linked + run IN-PROCESS (ART has no `java -jar` to fork): D8/R8
    // (the dexer/shrinker) and apksig (APK v1/v2/v3 signing). android-support keeps these compileOnly+test;
    // the device app is the one place they're actually bundled and dexed. The native aapt2/zipalign are not
    // here — they ship as jniLibs prebuilts (see fetchAndroidBuildTools below).
    implementation(libs.android.r8)
    implementation(libs.android.apksig)

    // Core-library desugaring runtime (temporarily enabled — see isCoreLibraryDesugaringEnabled above).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
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
    androidTestCompileOnly(libs.kotlin.compiler.embeddable)
    // The spike's Compose case references ComposeCompilerPlugin (lang-kotlin) to locate the bundled plugin
    // jar. Like the compiler API, lang-kotlin reaches the app only transitively, so add it compileOnly: the
    // type to compile against, with the app's dexed copy providing it at runtime.
    androidTestCompileOnly(project(":lang-kotlin"))
    // The Java-17-on-ART build spike calls JdtBatchCompiler (lang-jdt). Like the above, lang-jdt reaches the
    // app only transitively (via :ide-core), so add it compileOnly — the app's dexed copy provides it (and the
    // relocated ecj-art) at runtime.
    androidTestCompileOnly(project(":lang-jdt"))
}