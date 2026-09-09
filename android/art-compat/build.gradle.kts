import dev.ide.build.RelocateTypesInJar
// Imported (not fully-qualified) because the Java plugin's `java` project extension shadows the `java.*`
// package inside a build script — `java.io.File` would parse as `(java extension).io`.
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    `java-library`
}

// art-compat: the JDK/ART compatibility jars the app dexes but does not compile against.
//
// Four kinds of thing live here, all of them jars produced by this build rather than resolved from a
// repository: third-party jars ASM-relocated off APIs ART lacks (ecj's `java.lang.Runtime$Version`, the
// Eclipse runtime's `java.lang.StackWalker`), JDK API surface Android omits entirely but that app classes
// may legally define (`javax.xml.stream`, `javax.swing.Icon`, `javax.management.Notification*`,
// `javax.lang.model` via the checked-in java-compiler.jar), and headless shims for the rest
// (`javax.swing.SwingUtilities`, `jdk.jfr.*`).
//
// WHY THIS IS ITS OWN MODULE. These jars used to be declared in :ide-android as `implementation(files(...))`.
// A `files(...)` entry reaches AGP in `ArtifactScope.FILE`, which routes it to
// `desugar<Variant>FileDependencies` (`DexFileDependenciesTask`) instead of the ordinary per-artifact dexing
// transform. That task is non-incremental by design (AGP carries a `TODO: make incremental`), and it gives
// each file dependency its own worker that builds a `ClassFileProviderFactory` over the ENTIRE runtime
// classpath before dexing. On this app's ~250 MB classpath that fixed overhead measured ~20s per build for
// under 2 MB of actual input. Published as one module's artifacts instead, each jar goes through
// `DexingNoClasspathTransform`: content-keyed, cached under ~/.gradle/caches/*/transforms, and dexed once
// rather than on every build whose classpath ABI moved. With no file dependencies left,
// `desugar<Variant>FileDependencies` has no inputs at all and drops out of the graph.
//
// This module has no sources of its own (the artShims are compiled by a hand-rolled JavaCompile below,
// against android.jar rather than the JDK), so the java-library plugin's default `jar` artifact and its
// `classes`/`resources` secondary variants are replaced wholesale at the bottom of this file.

// --- ecj-on-ART patch (java.lang.Runtime$Version) -------------------------------------------------
// ecj's compiler reads `Runtime.Version` for its --release handling; the type is absent from ART, so
// relocate the references onto the shim :lang-jdt ships (dev.ide.lang.jdt.compat.RuntimeVersion) and dex the
// patched jar. :ide-android strips the stock module from every runtime classpath so only this copy is dexed.
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

// --- what this module publishes -------------------------------------------------------------------
// One variant carrying every compatibility jar. Gradle resolves a multi-artifact variant to the whole set,
// and AGP then transforms each jar to dex independently — which is the entire point of moving these off the
// `files(...)` path (see the module comment above).
//
// The java-library plugin's own (empty) `jar` artifact and its `classes`/`resources` secondary variants are
// removed: this module has no `src/main`, and a consumer's compile classpath asks for
// LibraryElements=classes, so leaving them would resolve to empty directories instead of these jars.
//
// NB the unpatched inputs (`ecjUnpatched`, `eclipseRuntimeUnpatched`) are resolve-only and are deliberately
// NOT declared as dependencies of this variant: shipping both a stock Eclipse jar and its ART-relocated copy
// is a duplicate-class failure at dex time. :ide-android additionally strips those three stock modules from
// its runtime classpaths, where they also arrive transitively via :lang-jdt.
val compatJars = listOf(
    relocateEcjForArt.flatMap { it.outputJar },
    relocateCoreRuntimeForArt.flatMap { it.outputJar },
    relocateEquinoxCommonForArt.flatMap { it.outputJar },
    artShimsJar.flatMap { it.archiveFile },
)
listOf("apiElements", "runtimeElements").forEach { elements ->
    configurations.named(elements) {
        outgoing.artifacts.clear()
        compatJars.forEach { outgoing.artifact(it) }
        // These two are plain `register` tasks (no typed output property), so name the file and carry the
        // task dependency with `builtBy`.
        outgoing.artifact(layout.buildDirectory.file("stax-api/stax-api.jar")) { builtBy(generateStaxApiJar) }
        outgoing.artifact(layout.buildDirectory.file("swing-api/swing-api.jar")) { builtBy(generateSwingApiJar) }
        // javax.lang.model + the javac API surface, checked in rather than generated: the app's Java
        // annotation-processing path (:lang-ksp, the JDT/ecj processor host) links against types Android
        // omits and that no JDK module here can supply verbatim.
        // `type` is explicit: it is a checked-in file rather than a task output, so nothing infers it, and
        // AGP's dexing transform chain is keyed on artifactType = jar.
        outgoing.artifact(layout.projectDirectory.file("libs/java-compiler.jar")) { type = "jar" }
        outgoing.variants.removeIf { it.name == "classes" || it.name == "resources" }
    }
}
