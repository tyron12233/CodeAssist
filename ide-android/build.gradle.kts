// Android launcher: a Compose Android application that renders the reusable IDE UI (:ide-ui) — the same
// commonMain composables the desktop launcher uses — over an Android [AndroidIdeBackend]. It is the
// Android counterpart to :ide-desktop. Under AGP 9, Kotlin is built into `com.android.application` (no
// kotlin-android plugin); Compose comes from the Compose Multiplatform + Compose-compiler plugins.
import com.android.build.gradle.internal.tasks.factory.dependsOn
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
    // Firebase, for push notifications. Declared but NOT applied here: the plugin fails the build when
    // google-services.json is missing, and a contributor cloning this repo has no reason to hold our
    // Firebase config. It is applied below only when that file exists; with it absent FirebaseApp never
    // initializes and the push code stays dormant, which the messaging service checks for rather than
    // assuming. (`file(...)` is not callable inside a `plugins` block, which is the other reason this is
    // two steps.)
    alias(libs.plugins.google.services) apply false
}

// firebase-common:22 depends on androidx.datastore, which drags in kotlin-parcelize-runtime and with it the
// deprecated `kotlin-android-extensions-runtime`. Its `kotlinx.android.parcel.*` annotations also live in
// `parcelize-compiler-plugin-for-ide`, which this app dexes deliberately (see the dependency below) so the
// on-device Kotlin compiler can build a user's @Parcelize classes — so two jars declare the same classes and
// `checkDebugDuplicateClasses` fails.
//
// Dropping the AndroidX-side copy is the right half to lose: those annotations stay available from the
// plugin jar, and the runtime a *user's* app needs is added to their project by its own Build Features
// toggle, never from this classpath. Scoped to the app's own compile/runtime classpaths rather than every
// configuration, because this build file also has configurations whose whole job is collecting jars to ship
// to the device, and those must keep resolving exactly what they ask for.
configurations.matching {
    it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")
}.configureEach {
    // Both halves of the Parcelize runtime: `kotlin-parcelize-runtime` owns `kotlinx.parcelize.*` and pulls
    // in `kotlin-android-extensions-runtime`, which owns the older `kotlinx.android.parcel.*`. The
    // `-for-ide` plugin jar contains BOTH sets, so excluding only one leaves the other colliding — which is
    // exactly what happened on the first attempt.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-parcelize-runtime")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}

// See the note in `plugins`: push is configured only in a checkout that has the Firebase config.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.lifecycle("ide-android: no google-services.json — building without push notifications.")
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
    // Lazy: resolve the configuration at execution time, not during configuration.
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

// --- Android compose-runtime classes.jar (androidTest VM interpret spike) ------------------------
// VmComposeRuntimeArtSpike interprets the real ANDROID compose-runtime bytecode with the :jvm-interp VM.
// The app's copy is dexed (no .class bytes on ART), so stage the artifact's classes.jar as an androidTest
// asset. This is the androidx artifact (not the desktop JAR above) so the Android actuals run against the
// real platform classes the bridge resolves on device.
val vmSpikeComposeRuntimeAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies { vmSpikeComposeRuntimeAar("androidx.compose.runtime:runtime-android:1.10.5@aar") }

val bundleVmSpikeComposeRuntimeAsset = tasks.register<Copy>("bundleVmSpikeComposeRuntimeAsset") {
    description = "Stage the androidx compose-runtime classes.jar as an androidTest asset for the VM interpret spike."
    from(vmSpikeComposeRuntimeAar.elements.map { zipTree(it.single().asFile) }) { include("classes.jar") }
    rename { "compose-runtime-android.jar" }
    into(layout.buildDirectory.dir("vm-spike-asset/vmbench"))
}

// --- Android material3 classes.jar (androidTest VM interpret spike) ------------------------------
// VmButtonArtSpike interprets a real DRAWING Material3 composable (Button -> Surface -> ripple -> Row) with
// the :jvm-interp VM against the app's real dexed Compose runtime/foundation/ui bridged — the piece the
// desktop harness can't reach (Material3 Surface graphics need Skiko, absent headless). Stage the artifact's
// classes.jar (non-transitive: only material3's own classes are interpreted; foundation/ui/runtime bridge).
val vmSpikeMaterial3Aar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies { vmSpikeMaterial3Aar("androidx.compose.material3:material3-android:1.4.0-beta01@aar") }

// --- owned java.awt/javax.swing fixture (androidTest VM interpret spike) --------------------------
// AwtToolkitArtSpike interprets a real Swing program on ART with its java.awt/javax.swing references remapped
// onto :awt-toolkit. The program is :awt-toolkit's own test fixture, compiled against the desktop JDK's REAL
// Swing exactly as a user's module would be, so it is STAGED from that module's test output rather than
// checked in: a committed .class would silently go stale the moment the fixture source changed.
val bundleAwtFixtureAsset = tasks.register<Copy>("bundleAwtFixtureAsset") {
    description = "Stage :awt-toolkit's compiled Swing fixture as an androidTest asset for AwtToolkitArtSpike."
    dependsOn(":awt-toolkit:testClasses")
    from(project(":awt-toolkit").layout.buildDirectory.dir("classes/java/test/swingfixture")) {
        include("SwingFixture*.class")
    }
    into(layout.buildDirectory.dir("vm-spike-asset/awt"))
}

val bundleVmSpikeMaterial3Asset = tasks.register<Copy>("bundleVmSpikeMaterial3Asset") {
    description = "Stage the androidx material3 classes.jar as an androidTest asset for the VM Button interpret spike."
    from(vmSpikeMaterial3Aar.elements.map { zipTree(it.single().asFile) }) { include("classes.jar") }
    rename { "material3-android.jar" }
    into(layout.buildDirectory.dir("vm-spike-asset/vmbench"))
}

// --- Full Compose stack, Android variants (androidTest: interpret foundation/ui, milestone A) ------
// VmTextFieldArtSpike against material3 1.5.0-alpha24 needs foundation classes newer than the app bundles
// (androidx.compose.foundation.style.MutableStyleState); the flip must interpret those from the project jars,
// not bridge to the bundled Compose. Stage the transitive material3 closure as REAL Android bytecode (not
// jvmstubs — those have no method bodies to interpret), so the VM can interpret foundation/ui/animation.
val vmStackAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE, objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "release"))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm)
    }
}
dependencies { vmStackAar("androidx.compose.material3:material3-android:1.5.0-alpha24") }
val bundleVmStackAsset = tasks.register("bundleVmStackAsset") {
    description = "Stage the transitive Compose stack (Android variants) as androidTest assets so the VM can interpret foundation/ui."
    val outDir = layout.buildDirectory.dir("vm-spike-asset/vmstack")
    val artifacts = vmStackAar.incoming.artifacts
    inputs.files(artifacts.artifactFiles)
    outputs.dir(outDir)
    doLast {
        val dst = outDir.get().asFile
        dst.mkdirs()
        dst.listFiles()?.forEach { it.delete() }
        artifacts.artifacts.forEach { ra ->
            val f = ra.file
            val id = ra.id.componentIdentifier.displayName.replace(Regex("[^A-Za-z0-9.-]"), "_")
            when {
                f.name.endsWith(".aar") -> copy { from(zipTree(f)) { include("classes.jar") }; into(dst); rename { "$id.jar" } }
                f.name.endsWith(".jar") -> copy { from(f); into(dst); rename { "$id.jar" } }
            }
        }
    }
}

// --- Moshi runtime jars (androidTest KSP-on-ART real-processor spike) -----------------------------
// KspArtSpikeTest.bundledMoshiRunsOnArt runs the REAL bundled Moshi processor on ART via the production
// KspSourceGenerator path. Moshi's runtime (com.squareup.moshi:moshi + okio) is pure JVM — plain jars, no
// AAR — so it stages cleanly as an androidTest asset for KSP's `libraries` classpath (where `@JsonClass`
// resolves). The processor itself comes from the app-bundled /processors/moshi.zip; only the runtime (data
// the module compiles against) is staged here.
val moshiArtLibs: Configuration by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = true
}
dependencies { moshiArtLibs("com.squareup.moshi:moshi:1.15.2") }
val bundleMoshiLibsAsset = tasks.register<Copy>("bundleMoshiLibsAsset") {
    description = "Stage the Moshi runtime jars (moshi + okio) as an androidTest asset for the KSP-on-ART spike."
    from(provider { moshiArtLibs.filter { it.name.endsWith(".jar") } })
    into(layout.buildDirectory.dir("moshi-libs-asset/moshi-libs"))
}

// --- applog-runtime asset (debug-only app-log bridge injected into user apps) --------------------
// The Android build system weaves this tiny jar (a ContentProvider + LocalSocket log forwarder) into DEBUG
// builds so a running app forwards its logs to the IDE's Logcat tab. It ships as a plain jar of .class files
// (compiled against a stub android.jar), dexed into the user's app by the normal external-dex path — so we
// stage it as an asset the app extracts to a file and hands to AndroidBuildSystem. It is a PROJECT artifact,
// so `from(configuration)` (NOT `.elements.map { it.asFile }`) is used: the configuration carries the
// `:applog-runtime:jar` task dependency, so the jar is built before this Copy runs (otherwise the asset dir
// is empty and the app throws FileNotFoundException on startup extracting it).
val appLogRuntimeArtifact: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { appLogRuntimeArtifact(project(":applog-runtime")) }

val bundleAppLogRuntimeAsset = tasks.register<Copy>("bundleAppLogRuntimeAsset") {
    description = "Stage the :applog-runtime jar as an asset (the debug-only in-app log bridge)."
    from(appLogRuntimeArtifact)
    into(layout.buildDirectory.dir("applog-runtime-asset"))
    rename { "applog-runtime.jar" }
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
// that cap (measured ceiling ~1.5GB). To run R8 there it needs its classes as a loadable dex, so the r8 tool
// jar is D8-dexed into a standalone r8.dex.zip asset that `dev.ide.android.R8ForkSupport` extracts and puts
// on `dalvikvm64 -Xmx<n>m -cp <asset> com.android.tools.r8.R8 …`.
//
// A fork CAN load the app's own APK instead (the persistent Kotlin compiler VM in `dev.ide.android.fork`
// does exactly that), which would make this asset unnecessary. It is kept because R8/D8 fork PER INVOCATION
// and a 180MB APK classpath costs ~800ms of class loading per fork against ~130ms for this asset, which the
// dex merge would pay several times a build.
//
// The zip carries the jar's RESOURCES as well as its dex. D8 emits `classes*.dex` only, so dexing alone
// silently drops `resources/new_api_database.ser` (R8's API-level database), the `META-INF/services` entry
// and `r8-version.properties`. R8 then warns "Could not find the api database at
// resources/new_api_database.ser" and emits different code than the same version run from the jar. With the
// resources folded back in, a forked run reproduces a host `java -cp r8.jar` run byte for byte.
val r8DexTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { r8DexTool(libs.android.r8) { isTransitive = false } }

val bundleR8DexAsset = tasks.register<JavaExec>("bundleR8DexAsset") {
    description = "D8-dex the R8 tool jar, with its resources, into a forked-VM-loadable r8.dex.zip asset."
    val outZip = layout.buildDirectory.file("r8-dex-asset/r8.dex.zip")
    val dexOnlyZip = layout.buildDirectory.file("r8-dex-asset/r8-dex-only.zip")
    classpath = r8DexTool                       // r8.jar contains D8 — self-dex it
    mainClass.set("com.android.tools.r8.D8")
    // Cacheable: `clean` deletes the asset and re-dexing r8.jar measured ~15s on every clean build, for a
    // pure function of one pinned jar. Classpath normalization keys on the jar's CONTENT, so the artifact's
    // location in the Gradle cache never enters the key. `args` is assembled in doFirst (after Gradle has
    // snapshotted inputs) but is derived entirely from this jar, so it adds nothing to the key.
    inputs.files(r8DexTool).withPropertyName("r8Tool").withNormalizer(ClasspathNormalizer::class)
    outputs.file(outZip).withPropertyName("r8DexZip")
    outputs.cacheIf("r8.dex.zip is a pure function of the pinned r8 jar") { true }
    // min-api 26 = the app's minSdk; the forked VM runs on the device's ART (>= 26), and a higher min-api
    // minimises desugaring (r8 is plain Java 8 bytecode), so no `--lib` platform is needed to dex it.
    doFirst {
        val dexOnly = dexOnlyZip.get().asFile
        dexOnly.parentFile.mkdirs(); dexOnly.delete()
        args = listOf(
            "--release",
            "--min-api", "26",
            "--output", dexOnly.absolutePath,
            r8DexTool.singleFile.absolutePath,
        )
    }
    // Combine D8's `classes*.dex` with every non-class entry of the source jar into one zip. A classloader
    // built over it then sees both the code and the resources, exactly as one built over the jar does.
    doLast {
        val dexOnly = dexOnlyZip.get().asFile
        val out = outZip.get().asFile
        out.delete()
        val written = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun copyEntries(from: File, keep: (ZipEntry) -> Boolean) {
                ZipFile(from).use { zf ->
                    zf.entries().asSequence().filter { !it.isDirectory && keep(it) }.forEach { e ->
                        if (!written.add(e.name)) return@forEach
                        zos.putNextEntry(ZipEntry(e.name))
                        zf.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            copyEntries(dexOnly) { it.name.endsWith(".dex") }
            // Everything the jar carries that is not code. The MANIFEST is dropped: it describes the jar, and
            // an inaccurate one on a dex classpath is worse than none.
            copyEntries(r8DexTool.singleFile) { !it.name.endsWith(".class") && it.name != "META-INF/MANIFEST.MF" }
        }
        dexOnly.delete()
        logger.lifecycle("bundleR8DexAsset: ${out.name} = ${out.length() / (1024 * 1024)}MB (dex + jar resources)")
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
// Google's TEST interstitial unit — the full-screen "long build" ad (AndroidAdHost.showBuildInterstitial).
val testAdmobInterstitialUnitId = "ca-app-pub-3940256099942544/1033173712"
// The real ids are baked in as the release defaults (AdMob ids are not secret — they ship inside every APK),
// and stay overridable so a fork can point ads at its own AdMob account instead of the upstream one.
val realAdmobAppId = (findProperty("ADMOB_APP_ID") as String?) ?: System.getenv("ADMOB_APP_ID")
    ?: "ca-app-pub-7523005242346905~2985774451"
val realAdmobNativeUnitId = (findProperty("ADMOB_NATIVE_UNIT_ID") as String?) ?: System.getenv("ADMOB_NATIVE_UNIT_ID")
    ?: "ca-app-pub-7523005242346905/7440024785"
// The real interstitial unit (full-screen "long build" ad). Baked in as the release default like the other
// ids (AdMob ids aren't secret — they ship in every APK), overridable via -PADMOB_INTERSTITIAL_UNIT_ID or the
// ADMOB_INTERSTITIAL_UNIT_ID env var so a fork can point at its own AdMob account.
val realAdmobInterstitialUnitId = (findProperty("ADMOB_INTERSTITIAL_UNIT_ID") as String?)
    ?: System.getenv("ADMOB_INTERSTITIAL_UNIT_ID")
    ?: "ca-app-pub-7523005242346905/6735189457"

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
        versionCode = 86
        versionName = "3.13.0"
        // connectedAndroidTest harness (the on-device Kotlin-compiler discovery spike).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Supabase project URL + *publishable* key, shared by every feature that talks to it: opt-in
        // usage analytics (docs/analytics.md) and the Projects Store catalog (supabase/README.md). One pair
        // of fields rather than per-feature copies, because it is one project and a rotated key has to reach
        // both at once.
        //
        // Overridable per-build via -PSUPABASE_URL / -PSUPABASE_KEY or the SUPABASE_URL / SUPABASE_KEY env
        // vars, so the endpoint or key can rotate without a code change.
        //
        // The publishable key is safe to ship in an open-source client ONLY because row-level security is
        // what actually gates access: INSERT-only on `events`, and read-approved-rows-only on the `store_*`
        // tables. An empty URL leaves both features wired but inert (analytics falls back to the no-op
        // service; the store falls back to the bundled catalog), so a fork can build with no endpoint.
        // Analytics collection still never happens without the user's explicit consent.
        val supabaseUrl = (findProperty("SUPABASE_URL") as String?) ?: System.getenv("SUPABASE_URL")
            ?: "https://lqlpkeummmmglikumotx.supabase.co"
        val supabaseKey = (findProperty("SUPABASE_KEY") as String?) ?: System.getenv("SUPABASE_KEY")
            ?: "sb_publishable_5T14bUAG6fOGz47kwYzG7A_25dj3ap4"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")

        // AdMob defaults = Google TEST ids. Debug inherits these as-is; `release` overrides to the real ids
        // below, and `profile` (a local perf build) is forced back to test. The App id reaches the manifest
        // via ${admobAppId}; the native ad-unit id is read from BuildConfig by AndroidAdHost.
        manifestPlaceholders["admobAppId"] = testAdmobAppId
        buildConfigField("String", "AD_NATIVE_UNIT_ID", "\"$testAdmobNativeUnitId\"")
        buildConfigField("String", "AD_INTERSTITIAL_UNIT_ID", "\"$testAdmobInterstitialUnitId\"")
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
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("agent-ui-strings-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("vcs-ui-strings-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("compose-drawables-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("r8-dex-asset").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("applog-runtime-asset").get().asFile)
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("vm-spike-asset").get().asFile)
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("moshi-libs-asset").get().asFile)

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
            buildConfigField("String", "AD_INTERSTITIAL_UNIT_ID", "\"$realAdmobInterstitialUnitId\"")
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
            buildConfigField("String", "AD_INTERSTITIAL_UNIT_ID", "\"$testAdmobInterstitialUnitId\"")
        }
        // EXPERIMENTAL, non-shipping: an R8-minified build used only to measure how far the app's own
        // dex (~61% of the APK, mostly the bundled Kotlin compiler + IntelliJ platform) can shrink. The
        // shipping `release` build keeps R8 OFF (see above) because the toolchain is loaded reflectively;
        // this variant explores that "revisit with keep rules" note behind conservative keep rules
        // (proguard-rules-minified.pro keeps the reflective toolchain wholesale and tree-shakes only the
        // safe libraries). Build with `:ide-android:assembleMinified` — R8 whole-program on this input is
        // memory-hungry, so bump org.gradle.jvmargs (~8g) for the run. NOT runtime-validated: a minified
        // build can boot and still break when it compiles/dexes a user project, so never ship it without
        // exercising the full toolchain (compile -> dex -> sign -> run -> completion) on-device.
        create("minified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = false // isolate code shrinking; resources aren't the bulk
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules-minified.pro",
            )
            // Sign with the release/upload key when configured, else the debug key so it installs locally.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Non-shipping: keep Google TEST ad ids (initWith(release) copied the real ones).
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "AD_NATIVE_UNIT_ID", "\"$testAdmobNativeUnitId\"")
            buildConfigField("String", "AD_INTERSTITIAL_UNIT_ID", "\"$testAdmobInterstitialUnitId\"")
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
                // bundletool ships pre-dexed "archived app" stubs (archive/dex/*/classes.dex) used only by
                // Play's app-archiving, which the on-device build never invokes. Dead weight in every build,
                // and their `.dex`-alongside-`.class` mixing in the jar is exactly what makes whole-program
                // R8 refuse the archive ("Cannot create android app from an archive containing both DEX and
                // Java-bytecode content"). Dropping them keeps bundletool's transitives intact.
                "com/android/tools/build/bundletool/archive/dex/**",
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

    androidResources {
        // The bulk of this app's assets are archives that are ALREADY deflated: the bundled android.jar
        // (26 MB), r8.dex.zip, kotlinc-resources.zip, kotlin-stdlib.jar, compose-runtime.jar,
        // applog-runtime.jar, core-lambda-stubs.jar. Both `compress<Variant>Assets` and `package<Variant>`
        // deflate every asset, so those ~38 MB are re-compressed twice per variant for a saving that
        // re-deflating an already-deflated stream cannot produce. Storing them costs no meaningful APK size
        // and makes the runtime extraction (AndroidSdkInstaller / R8ForkSupport read these straight out of
        // the APK) a copy rather than an inflate.
        //
        // Deliberately NOT listed: "dex". `noCompress` also governs the APK's own classes*.dex, and this
        // app carries ~130 MB of dex — storing that would roughly double the download.
        noCompress += setOf("jar", "zip")
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
    dependsOn(fetchAndroidBuildTools, bundleKotlinStdlibAsset, bundleKotlincResourcesAsset, bundleComposeRuntimeAsset, bundleComposeFontsAsset, bundleComposeStringAsset, bundleAgentUiComposeStringAsset, bundleVcsUiComposeStringAsset, bundleComposeDrawablesAsset, bundleR8DexAsset, bundleAppLogRuntimeAsset, bundleVmSpikeComposeRuntimeAsset, bundleVmSpikeMaterial3Asset, bundleVmStackAsset, bundleMoshiLibsAsset, bundleAwtFixtureAsset)
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

// The SAME Android packaging gap, for :agent-ui's OWN compose-resource strings (the chat_* i18n keys, which
// live in this module's composeResources under package dev.ide.agent.ui.generated.resources — migrated out of
// :ide-ui). Its compiled `.cvr` files must be staged under THAT resClass-package segment; without this the
// agent chat panel crashes on device with `MissingResourceException: composeResources/
// dev.ide.agent.ui.generated.resources/values/strings.commonMain.cvr`. Mirrors bundleComposeStringAsset.
val bundleAgentUiComposeStringAsset = tasks.register<Copy>("bundleAgentUiComposeStringAsset") {
    description = "Stage :agent-ui's i18n compose-resource strings into the APK assets (Android packaging gap)."
    dependsOn(":agent-ui:desktopProcessResources")
    from(project(":agent-ui").layout.buildDirectory.dir("processedResources/desktop/main/composeResources/dev.ide.agent.ui.generated.resources")) {
        include("values*/**/*.cvr")
    }
    into(layout.buildDirectory.dir("agent-ui-strings-asset/composeResources/dev.ide.agent.ui.generated.resources"))
}

// The SAME Android packaging gap, for :vcs-ui's OWN compose-resource strings (the vcs_* i18n keys, under
// package dev.ide.vcs.ui.generated.resources). Without this the Git panel crashes on device the moment it
// renders, with `MissingResourceException: composeResources/dev.ide.vcs.ui.generated.resources/values/
// strings.commonMain.cvr`. Mirrors bundleAgentUiComposeStringAsset.
val bundleVcsUiComposeStringAsset = tasks.register<Copy>("bundleVcsUiComposeStringAsset") {
    description = "Stage :vcs-ui's i18n compose-resource strings into the APK assets (Android packaging gap)."
    dependsOn(":vcs-ui:desktopProcessResources")
    from(project(":vcs-ui").layout.buildDirectory.dir("processedResources/desktop/main/composeResources/dev.ide.vcs.ui.generated.resources")) {
        include("values*/**/*.cvr")
    }
    into(layout.buildDirectory.dir("vcs-ui-strings-asset/composeResources/dev.ide.vcs.ui.generated.resources"))
}

// The staging tasks above are easy to forget, and forgetting one is invisible until the app runs on a device:
// desktop and unit tests load composeResources through the normal JVM resources route, so a module whose
// strings never reach the APK assets compiles and tests green, then throws MissingResourceException on the
// first render of the UI that reads them. That has shipped before. Assert the invariant during the assets
// merge instead: every module carrying its own string resources must land its compiled `.cvr` under its own
// resClass package. The expected packages are read from each module's `packageOfResClass`, so a new UI module
// is covered without touching this check.
val composeStringPackages: Map<String, String> = rootProject.subprojects
    .filter { it.file("src/commonMain/composeResources/values/strings.xml").exists() }
    .mapNotNull { module ->
        val script = module.file("build.gradle.kts").takeIf { it.exists() } ?: return@mapNotNull null
        val declared = Regex("packageOfResClass\\s*=\\s*\"([^\"]+)\"").find(script.readText())
        declared?.let { module.path to it.groupValues[1] }
    }
    .toMap()

tasks.matching { it.name.matches(Regex("merge(Debug|Profile|Release|Minified)Assets")) }.configureEach {
    val expected = composeStringPackages
    doLast {
        val merged = outputs.files.files.firstOrNull() ?: return@doLast
        val missing = expected.filterValues { pkg ->
            !File(merged, "composeResources/$pkg/values/strings.commonMain.cvr").exists()
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Compose string resources never reached the APK assets:")
                    missing.forEach { (module, pkg) -> appendLine("  $module -> composeResources/$pkg") }
                    append(
                        "Each Compose Multiplatform module with its own composeResources needs a staging Copy " +
                            "task here (see bundleVcsUiComposeStringAsset), an assets.srcDir for its output " +
                            "directory, and an entry in preBuild's dependsOn. Without one the module's UI " +
                            "throws MissingResourceException on device.",
                    )
                },
            )
        }
    }
}

// The stock Eclipse jars we relocate for ART (ecj, core.runtime, equinox.common) reach the app's runtime
// classpath through several project dependencies: :ide-core (excluded inline below), but also :android-support
// and :layout-preview-impl, which depend on :lang-jdt without that exclude. Dexing both the stock jar and its
// ART-relocated copy is a duplicate-class (checkDuplicateClasses) and duplicate-resource (mergeJavaResource)
// failure, so strip the three stock modules from every runtime classpath; only :art-compat's relocated copies
// get dexed. Scoped to *RuntimeClasspath so the androidTest compile classpath still resolves lang-jdt's
// ecj/runtime types when compiling the on-device spike.
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
    // patched). See :art-compat's `relocateEcjForArt`.
    implementation(project(":ide-core")) {
        exclude(group = "org.eclipse.jdt", module = "ecj")
        // Drop the stock core.runtime / equinox.common: the StackWalker-relocated copies arrive from
        // :art-compat (relocateCoreRuntimeForArt / relocateEquinoxCommonForArt). This handles the :ide-core
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
    // The Supabase-backed store catalog the launcher registers against STORE_CATALOG_SOURCE.
    implementation(project(":store-impl"))
    // The logging facade (Log) — used directly here for the main-thread guard + the analytics log sink. It
    // reaches :ide-core only as `implementation` (not transitive), so depend on it explicitly.
    implementation(project(":platform-core"))
    // The plugin SPI: ApkPluginSource implements PluginSource/DiscoveredPlugin so the engine can load plugins
    // the user installed as separate apps. Reaches :ide-core only as `implementation`, hence explicit here.
    implementation(project(":plugin-api"))
    // The JDK/ART compatibility jars: ecj + the Eclipse runtime relocated off `java.lang.Runtime$Version`
    // and `java.lang.StackWalker`, the javax.lang.model / javax.xml.stream / javax.swing / javax.management
    // surface Android omits but the dexed ecj + IntelliJ platform link against, and the javax.swing
    // SwingUtilities / jdk.jfr headless shims. All of it is dexed into the APK and none of it is compiled
    // against; :art-compat produces the set. It is a MODULE rather than a handful of `implementation(files(..))`
    // entries so each jar is dexed by AGP's cached per-artifact transform instead of the non-incremental,
    // whole-classpath `desugar<Variant>FileDependencies` task — see art-compat/build.gradle.kts.
    implementation(project(":art-compat"))

    // (gnu.trove and the platform support libs now arrive transitively via :kotlin-compiler-deps, the
    // unshaded compiler dependency set that :lang-kotlin api-consumes.)

    // The Jetpack Compose kotlinc plugin's classes — dexed into the app so kotlinc can resolve its
    // `ComposePluginRegistrar` on ART. The build feeds the plugin to the in-process K2JVMCompiler via
    // `-Xplugin` (the jar is the lang-kotlin bundled resource); kotlinc reads the service descriptor from
    // that jar but defines the registrar class through parent delegation to the app classloader (a jar's
    // bytecode can't be loaded at runtime on ART), so the class must live here. Non-transitive: it needs
    // only its own classes — the (unshaded) Kotlin compiler it builds on is already dexed via :lang-kotlin.
    implementation(libs.kotlin.compose.compiler.plugin.ide) { isTransitive = false }

    // The kotlinx.serialization kotlinc plugin's classes — dexed into the app for the same reason as the
    // Compose plugin above: kotlinc reads the service descriptor from the bundled `-Xplugin` jar but resolves
    // `SerializationComponentRegistrar` through parent delegation to the app classloader on ART. Non-transitive
    // (it needs only its own classes; the unshaded compiler it builds on is already dexed via :lang-kotlin).
    implementation(libs.kotlin.serialization.compiler.plugin.ide) { isTransitive = false }

    // The kotlin-parcelize kotlinc plugin's classes — dexed into the app for the same reason as the Compose
    // and serialization plugins above: kotlinc resolves `ParcelizeComponentRegistrar` through parent delegation
    // to the app classloader on ART. Non-transitive (only its own classes; the compiler is dexed via :lang-kotlin).
    implementation(libs.kotlin.parcelize.compiler.plugin.ide) { isTransitive = false }

    // build-engine's ProgramInterpreter port + jvm-build's VmProgramInterpreter (kept `implementation` in
    // :ide-core, so not transitive): :ide-android constructs the interpreter with a dexing peer factory so a
    // Java/Kotlin console `run` executes on the bytecode VM on ART.
    implementation(project(":build-engine"))
    implementation(project(":jvm-build"))

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
    // FCM, for push notifications. Present unconditionally so the messaging code compiles in every
    // checkout; without google-services.json the SDK has no project to talk to and stays dormant, which
    // is exactly what a contributor's build should do.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.play.services.ads) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    // UMP/GDPR consent (User Messaging Platform). Gathered on launch BEFORE MobileAds.initialize, so EEA/UK
    // users see a certified consent form and personalized-ad fill isn't blocked. See AdConsentManager.
    implementation(libs.user.messaging.platform)
    // AdMob mediation adapters — Meta / Pangle / Mintegral bid against AdMob to fill the same NATIVE slots
    // (higher eCPM via competition; no new placements). Only native-capable networks are wired. Each pulls its
    // network SDK transitively; the same protobuf-lite dup-class rule as the ads SDK applies, and their SDKs may
    // add further transitive collisions that only surface at dex time — so a full assemble must be re-verified
    // when bumping these. Console-side mediation groups + per-network onboarding are configured in AdMob.
    implementation(libs.admob.mediation.meta) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    implementation(libs.admob.mediation.pangle) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    implementation(libs.admob.mediation.mintegral) {
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
    // The bytecode VM: the real-view layout preview interprets library/user View classes (dev.ide.jvm.Vm via
    // VmViewFactory) instead of dexing them, and DexPeerFactory realizes their peers. Reaches the app
    // transitively through :interp-compose's jvmShared (api), but the real-view code uses it directly.
    implementation(project(":jvm-interp"))
    // The plugin-facing interpreter's engine, for the one thing the launcher owns: registering the peer
    // factory a bytecode session needs on ART (see VM_PEER_FACTORY in AndroidIde).
    implementation(project(":interp-impl"))

    // The owned java.awt/javax.swing toolkit. It has to be in the APP dex, not the test APK: an interpreted
    // program's window class reaches ART as a peer that SUBCLASSES `dev.ide.swing.JPanel`, so the toolkit must
    // be loadable by the app class loader the generated dex is defined against. Nothing in the IDE calls it
    // yet (no run path is wired to it); AwtToolkitArtSpike is what exercises it on device.
    implementation(project(":awt-toolkit"))

    // On-device instrumentation: the Kotlin-compiler-on-ART discovery spike.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // On-device throughput benchmark for the :jvm-interp bytecode interpreter. Not in the app dex, so it is
    // included in the test APK; its asm dependency resolves against the app's dexed copy at runtime.
    androidTestImplementation(project(":jvm-interp"))
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
    // KspArtSpikeTest drives KSP2 on ART: BundledKspThin/BundledKspProcessors (lang-ksp) + the KSP2 SPI/config
    // (KSPJvmConfig/SymbolProcessorProvider/…). All reach the app only transitively via :ide-core → :lang-ksp
    // (implementation), so compile against them here; the app's dexed copies provide them at runtime.
    androidTestCompileOnly(project(":lang-ksp"))
    androidTestCompileOnly(libs.ksp.api)
    androidTestCompileOnly(libs.ksp.common.deps)
}

// ============================================================================
// R8 input fix for the experimental `minified` variant. The global
// packaging.resources.excludes above drops bundletool's dead archive-dex stubs
// from every build's PACKAGED OUTPUT, but whole-program R8 reads the dependency
// jar directly (not the packaged resources), so it still sees the `.class`+`.dex`
// mix and refuses it. Feed R8 a dex-stripped copy of the jar for `minified`
// only. Scoped here (not global) because (a) the shipping R8-off builds don't
// need it, and (b) doing it globally without dropping bundletool's protobuf/
// dagger transitives (which resolve only through the bundletool module) needs a
// transitive-preserving jar swap AGP doesn't cleanly allow — worth solving only
// if/when R8 is enabled on a shipping variant. CONSEQUENCE for `minified`: the
// module-exclude drops that protobuf/dagger closure, so the minified APK is a
// slight under-estimate of a correct minified build (a few MB would return).
val bundletoolNoDexSource: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { bundletoolNoDexSource(libs.android.bundletool) { isTransitive = false } }

val stripBundletoolDex = tasks.register<Jar>("stripBundletoolDex") {
    description = "Repackage bundletool without its embedded .dex stubs so whole-program R8 can ingest it."
    archiveFileName.set("bundletool-nodex.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { zipTree(bundletoolNoDexSource.singleFile) }) { exclude("**/*.dex") }
}

configurations.matching { it.name.startsWith("minified") }.configureEach {
    exclude(group = "com.android.tools.build", module = "bundletool")
}
dependencies { "minifiedImplementation"(files(stripBundletoolDex)) }