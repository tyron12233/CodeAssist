import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// kotlin-compiler-deps: the ONE unshaded Kotlin compiler + IntelliJ platform dependency set.
//
// Historically the IDE ran TWO IntelliJ platforms: kotlin-compiler-embeddable (relocated
// `org.jetbrains.kotlin.com.intellij.*`) for the editor parse host + the build's K2JVMCompiler, and the
// un-relocated `-for-ide` split + `com.jetbrains.intellij.platform` jars for the K2 Analysis API (which
// therefore had to load in an isolated classloader). This module unifies them: it merges the `-for-ide`
// split compiler (parser/PSI, FIR, JVM backend, AND the CLI driver `K2JVMCompiler` via
// kotlin-compiler-cli-for-ide) with the real IntelliJ platform jars, so :lang-kotlin and :lang-kotlin-aa
// share one PSI/compiler world.
//
// The output is ONE deduped jar (mergeUnshadedCompiler), not the raw jar set: the compiler `-for-ide` jars
// re-bundle a handful of platform classes (MockProject, CoreProgressManager, ObjectNode, ...), which AGP's
// checkDuplicateClasses/D8 reject as duplicate classes. First-wins with the PLATFORM jars first — the same
// winner classpath ordering gives on the desktop JVM — and META-INF/services concatenated (the aa-runtime
// MergeAaJars recipe). The merge also performs the coroutines-facade remap: the 251.x platform references
// `kotlinx.coroutines.internal.intellij.IntellijCoroutines` (JetBrains' coroutines fork; standard
// coroutines lacks it), so those refs are ASM-remapped to `com.intellij.util.IntelliJCoroutinesFacade` and
// the vendored facade (libs/intellij-coroutines-facade.jar, extracted from KSP's symbol-processing-aa) is
// folded into the merged jar. Same fix as KSP.
//
// Versions: `kotlinForIde` + `intellijPlatform` in libs.versions.toml (they must move together; the pair
// matches KSP2 main). The artifacts live on packages.jetbrains.team, not Maven Central - see the scoped
// repositories in settings.gradle.kts.

val kotlinForIde: String = libs.versions.kotlinForIde.get()
val intellijPlatform: String = libs.versions.intellijPlatform.get()

val platformJars: Configuration by configurations.creating { isTransitive = false }
val compilerJars: Configuration by configurations.creating { isTransitive = false }
val analysisApiJars: Configuration by configurations.creating { isTransitive = false }
// streamex is folded into the merged jar rather than kept as a module dep: it is a REAL multi-release jar
// (versioned classes, not just module-info), which AGP 9's dexing transform chain refuses to consume
// (artifactType multi-release-jar has no android-classes-jar transform). Inside the merged FILE dependency
// it rides the file-dependency desugar/dex path like the compiler classes; its base (Java 8) classes are
// what D8 dexes, and the desktop JVM still honors the versioned entries via the merged Multi-Release manifest.
val mergedSupportJars: Configuration by configurations.creating { isTransitive = false }

dependencies {
    // --- IntelliJ platform (un-relocated com.intellij.*) ----------------------------------------------
    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
    ).forEach { platformJars("$it:$intellijPlatform") }

    // --- The `-for-ide` split compiler ---------------------------------------------------------------
    // common = PSI/parser + cli-base (KotlinCoreEnvironment); fir = the K2 frontend; ir + fe10 = the IR/JVM
    // backend + codegen; cli = the driver (K2JVMCompiler). Together they replace kotlin-compiler-embeddable
    // for BOTH the editor parse host and the build's in-process kotlinc.
    listOf(
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-cli-for-ide",
    ).forEach { compilerJars("$it:$kotlinForIde") }

    // --- The Kotlin Analysis API (same `-for-ide` train) ---------------------------------------------
    // Folded into the ONE merged jar since the AA runtime collapse: the app dexes these directly (no more
    // aa.apk double-shipping) and the desktop runs them in-process. Ordered AFTER the platform + compiler
    // groups so any re-bundled copies in these jars lose first-wins, matching the old classpath order.
    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
    ).forEach { analysisApiJars("$it:$kotlinForIde") }

    mergedSupportJars("one.util:streamex:0.7.2")
}

// Merge platform + compiler jars into one deduped jar. First-wins in iteration order: platform jars first
// (their com.intellij.* copies win over the compiler's re-bundled subset, matching desktop classpath
// order), each group sorted by file name for determinism, the coroutines facade last. `.class` entries get
// the IntellijCoroutines -> IntelliJCoroutinesFacade remap; META-INF/services files are concatenated; jar
// signature files and per-jar manifests are dropped (a fresh Multi-Release manifest is written so the
// platform jars' META-INF/versions/* variants stay honored on the desktop JVM).
abstract class MergeUnshadedCompiler : DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles abstract val platformJars: ConfigurableFileCollection
    @get:org.gradle.api.tasks.InputFiles abstract val compilerJars: ConfigurableFileCollection
    @get:org.gradle.api.tasks.InputFiles abstract val analysisApiJars: ConfigurableFileCollection
    @get:org.gradle.api.tasks.InputFiles abstract val extraJars: ConfigurableFileCollection
    @get:org.gradle.api.tasks.OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction
    fun merge() {
        val remapper = object : Remapper() {
            override fun map(internalName: String): String =
                if (internalName == "kotlinx/coroutines/internal/intellij/IntellijCoroutines")
                    "com/intellij/util/IntelliJCoroutinesFacade" else super.map(internalName)
        }
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        val seen = HashSet<String>()
        val services = LinkedHashMap<String, StringBuilder>()
        val inputs = platformJars.files.sortedBy { it.name } +
            compilerJars.files.sortedBy { it.name } +
            analysisApiJars.files.sortedBy { it.name } +
            extraJars.files.sortedBy { it.name }
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF").apply { time = 315532800000L })
            zos.write("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray())
            zos.closeEntry()
            inputs.filter { it.isFile }.forEach { jar ->
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name == "META-INF/MANIFEST.MF") continue
                        if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue
                        // The platform util jar bundles a log4j API shim and a couple of org.jetbrains
                        // annotations; the SEPARATE support deps (intellij-deps log4j, annotations-24)
                        // carry the complete versions of both, so keep those and drop the bundled subset
                        // (otherwise D8/checkDuplicateClasses rejects the pair as duplicate classes).
                        if (name.startsWith("org/apache/log4j/") || name.startsWith("org/jetbrains/annotations/")) continue
                        val data = zip.getInputStream(entry).readBytes()
                        if (name.startsWith("META-INF/services/")) {
                            services.getOrPut(name) { StringBuilder() }.append(String(data)).append('\n')
                            continue
                        }
                        if (!seen.add(name)) continue
                        val bytes = if (name.endsWith(".class")) {
                            val cw = ClassWriter(0)
                            ClassReader(data).accept(ClassRemapper(cw, remapper), 0)
                            cw.toByteArray()
                        } else data
                        zos.putNextEntry(ZipEntry(name).apply { time = 315532800000L }) // fixed -> reproducible
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            }
            services.forEach { (n, sb) ->
                zos.putNextEntry(ZipEntry(n).apply { time = 315532800000L })
                zos.write(sb.toString().toByteArray())
                zos.closeEntry()
            }
        }
    }
}

val mergeUnshadedCompiler = tasks.register<MergeUnshadedCompiler>("mergeUnshadedCompiler") {
    // The task properties shadow the outer configuration vals inside this lambda; go via the container.
    platformJars.from(project.configurations.getByName("platformJars"))
    compilerJars.from(project.configurations.getByName("compilerJars"))
    analysisApiJars.from(project.configurations.getByName("analysisApiJars"))
    extraJars.from(files("libs/intellij-coroutines-facade.jar"))
    extraJars.from(project.configurations.getByName("mergedSupportJars"))
    outputJar.set(layout.buildDirectory.file("unshaded-compiler/kotlin-compiler-unshaded-$kotlinForIde.jar"))
}

dependencies {
    // The merged compiler+platform jar IS this module's API.
    api(files(mergeUnshadedCompiler.flatMap { it.outputJar }))

    // --- Support libs the platform/compiler need at runtime ------------------------------------------
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    api("com.google.guava:guava:33.2.0-jre")
    api("org.jetbrains.intellij.deps:asm-all:9.0")
    api("org.codehaus.woodstox:stax2-api:4.2.1") { isTransitive = false }
    api("com.fasterxml:aalto-xml:1.3.0") { isTransitive = false }
    api("com.github.ben-manes.caffeine:caffeine:2.9.3")
    api("org.jetbrains.intellij.deps.jna:jna:5.9.0.26") { isTransitive = false }
    api("org.jetbrains.intellij.deps.jna:jna-platform:5.9.0.26") { isTransitive = false }
    api(libs.trove4j)
    api("org.jetbrains.intellij.deps:log4j:1.2.17.2") { isTransitive = false }
    api("org.jetbrains.intellij.deps:jdom:2.0.6") { isTransitive = false }
    api("io.javaslang:javaslang:2.0.6")
    // io.vavr (javaslang's successor; DIFFERENT package): the compiler's Java classfile reader
    // (ClassifierResolutionContext) uses vavr collections un-relocated. Version = Kotlin repo's
    // versions.vavr. Without it every K2JVMCompiler compile dies with NoClassDefFoundError io/vavr/....
    api("io.vavr:vavr:0.10.4")
    api("javax.inject:javax.inject:1")
    api("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    api("org.lz4:lz4-java:1.7.1") { isTransitive = false }
    api("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.14-jb1") { isTransitive = false }
    api("org.jetbrains:annotations:24.1.0")
    api("io.opentelemetry:opentelemetry-api:1.34.1") { isTransitive = false }
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

// --- kotlinc-resources.zip (the on-device compiler's resource home) --------------------------------
// On ART the compiler's classes are dexed, but IntelliJ-core boots its extension registry by reading XML
// descriptors (META-INF/extensions/*.xml, plugin.xml, ...) from a real filesystem path. :ide-android ships
// the merged jar's non-class resources as an asset the app extracts at runtime (published in the
// `kotlinc.art.home` property; see build-logic PathUtilSelfLocatePass). This used to be built by stripping
// kotlin-compiler-embeddable.
abstract class CompilerResourcesZip : DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles abstract val inputJars: ConfigurableFileCollection
    @get:org.gradle.api.tasks.OutputFile abstract val outputZip: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputZip.get().asFile
        out.parentFile.mkdirs()
        var kept = 0
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            inputJars.files.filter { it.isFile && it.name.endsWith(".jar") }.sortedBy { it.name }.forEach { jar ->
                ZipFile(jar).use { zip ->
                    for (e in zip.entries()) {
                        if (e.isDirectory || e.name.endsWith(".class")) continue
                        zos.putNextEntry(ZipEntry(e.name).apply { time = 315532800000L })
                        zip.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                        kept++
                    }
                }
            }
        }
        logger.lifecycle("compilerResourcesZip: kept $kept non-class resource(s) -> ${out.name}")
    }
}

val compilerResourcesZip = tasks.register<CompilerResourcesZip>("compilerResourcesZip") {
    inputJars.from(mergeUnshadedCompiler.flatMap { it.outputJar })
    outputZip.set(layout.buildDirectory.file("kotlinc-resources/kotlinc-resources.zip"))
}

// :ide-android consumes this as the kotlinc-resources.zip asset.
val kotlincResourcesElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(kotlincResourcesElements.name, compilerResourcesZip.flatMap { it.outputZip })
