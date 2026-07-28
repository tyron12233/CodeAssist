plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-ksp — KSP2-based build-time source generation (Room, Moshi, Hilt, …).
//
// KspSourceGenerator (task 3) implements build-api's SourceGenerator SPI: it maps a module's sources +
// compile classpath onto a KSP2 KSPJvmConfig, loads the module's SymbolProcessorProviders through a
// KotlinPluginLoader (URLClassLoader on desktop, DexClassLoader on ART — the same loader the kotlinc
// compiler plugins use), and runs KSP2's standalone `KotlinSymbolProcessing(...).execute()` over the module,
// emitting into the ContentRole.GENERATED root the build's `generateSources` task manages. So a generated
// DAO/serializer compiles and indexes like hand-written code with no compile-task change.
//
// KSP2's `-aa-embeddable` runner renames its bundled Analysis API / IntelliJ platform, so it coexists on a
// classpath with :kotlin-compiler-deps' unshaded `-for-ide` compiler with no name clash. See
// docs/kotlin-compiler-plugins-and-codegen.md.
dependencies {
    api(project(":build-api")) // the SourceGenerator SPI (+ project-model-api / platform-core, transitively)

    // Ship ONLY the thin KSP SPI + config (a few hundred KB): KspSourceGenerator builds a KSPJvmConfig, a
    // KSPLogger, and holds the loaded SymbolProcessorProviders as these types. The heavy ~78 MB runner
    // (symbol-processing-aa-embeddable, which carries `com.google.devtools.ksp.impl.KotlinSymbolProcessing`
    // + its own relocated Analysis API) is NOT a static dep — it is resolved + dex-cached by the host and
    // invoked REFLECTIVELY through KspProcessorLoader, so it never enters the APK (see the memory note /
    // docs/kotlin-compiler-plugins-and-codegen.md). Parent-first delegation makes the runner see THESE
    // (shipped) SPI/config types, so the config/logger/providers cross the classloader boundary as one type.
    implementation(libs.ksp.api)
    implementation(libs.ksp.common.deps)

    // Test-only: the de-risk spikes (KspEngineSpikeTest / RoomKspSpikeTest) reference KotlinSymbolProcessing
    // statically, so they need the runner on the test compile classpath. KspSourceGeneratorTest instead drives
    // the production reflective path. KSP's Analysis API frontend uses coroutines (the embeddable bundles a
    // relocated copy, but keep the plain one explicit for the spikes' static classpath).
    testImplementation(libs.ksp.aa.embeddable)
    testImplementation(libs.kotlinx.coroutines.core)
}

// Bundle THIN KSP as a classpath resource (`/ksp-thin.jar`): only KSP's own classes
// (`com/google/devtools/ksp/**`, ~776 KB) extracted from the non-embeddable `symbol-processing-aa`, with its
// bundled 78 MB Analysis API dropped. At runtime `BundledKspThin` extracts it and `KspSourceGenerator` loads
// it parented to a classloader that already carries OUR Analysis API (:kotlin-compiler-deps) — so KSP runs on
// the compiler we ALREADY ship. Proven by ThinKspOnOurAaSpikeTest. This is the <100 MB, Play-compliant path.
val kspAaSource: Configuration by configurations.creating { isTransitive = false }
dependencies { kspAaSource(libs.ksp.aa) }
val kspThinJar = tasks.register<Jar>("kspThinJar") {
    description = "Extracts KSP's own classes (com.google.devtools.ksp.**) from symbol-processing-aa; drops its bundled AA."
    archiveFileName.set("ksp-thin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/ksp-thin"))
    from(provider { zipTree(kspAaSource.singleFile) }) { include("com/google/devtools/ksp/**") }
}
tasks.processResources { from(kspThinJar) }

// Bundle the blessed processors' jars in-app (EXECUTED code → must ship with the app, never downloaded; Play
// DCL). Each processor's transitive closure is packaged as a `zip-of-jars` resource (`/processors/<id>.zip`,
// each entry one jar) — NOT merged, so there is no META-INF/services concatenation or duplicate-class hazard.
// `BundledKspProcessors` extracts the entries to a dir and hands them to the loader as one classpath, parented
// to our compiler/AA (so shared types like `symbol-processing-api` resolve to OUR version, parent-first).
//
// APK-size dedup: a bundle DROPS jars the app already ships (kotlin-stdlib / kotlinx-coroutines /
// symbol-processing-api|common-deps) — the processor's child loader is parented to the app, so those resolve
// from the parent regardless. kotlin-reflect / kotlinpoet / guava / antlr etc. stay (the app may not have them).
val appProvidedJarPrefixes = listOf("kotlin-stdlib", "kotlinx-coroutines", "symbol-processing-api", "symbol-processing-common-deps")

/** A resolvable config for [id]'s processor closure + a Zip packaging its (deduped) jars as /processors/<id>.zip. */
fun bundleProcessor(id: String, dep: Provider<*>) {
    val cfg = configurations.create("ksp_${id}_bundle")
    dependencies.add(cfg.name, dep)
    val zip = tasks.register<Zip>("ksp${id.replaceFirstChar { it.uppercase() }}ProcessorZip") {
        description = "Packages the $id KSP processor closure as /processors/$id.zip (zip of jars; app-provided jars dropped)."
        archiveFileName.set("$id.zip")
        destinationDirectory.set(layout.buildDirectory.dir("generated/processors"))
        from(provider { cfg.filter { f -> f.name.endsWith(".jar") && appProvidedJarPrefixes.none { f.name.startsWith(it) } } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    tasks.processResources { from(zip) { into("processors") } }
}

bundleProcessor("room", libs.room.compiler)
bundleProcessor("moshi", libs.moshi.kotlin.codegen)
bundleProcessor("hilt", libs.hilt.compiler)
bundleProcessor("glide", libs.glide.ksp)

// Room desktop spike (task 2): resolve Room as ISOLATED classpaths (NOT on the test compile classpath) so the
// spike loads room-compiler's SymbolProcessorProvider through a URLClassLoader+ServiceLoader — the real
// processor-loading path production takes — and feeds room-runtime as the KSP `libraries`. The paths are
// handed to the test as system properties. Self-gates (assumeTrue) when unresolvable.
val roomProcessor: Configuration by configurations.creating   // room-compiler + its processor closure
val roomLibs: Configuration by configurations.creating        // room-runtime/-common annotations + RoomDatabase
val moshiLibs: Configuration by configurations.creating       // moshi runtime (JsonClass marker + KSerializer machinery)
// The KSP2 runner as an isolated classpath — KspSourceGeneratorTest hands its path to KspSourceGenerator's
// loader (the production reflective path), mirroring how the app will feed the bundled runner jar.
val kspRunner: Configuration by configurations.creating
// The thin-KSP feasibility spike (ThinKspOnOurAaSpikeTest): the non-embeddable runner (we extract only KSP's
// own classes from it) + OUR compiler/AA (:kotlin-compiler-deps) + stdlib/coroutines. The spike runs KSP's
// impl against our Analysis API to see if we can drop the 78 MB bundled platform entirely.
val kspThinRuntime: Configuration by configurations.creating
dependencies {
    roomProcessor(libs.room.compiler)
    roomLibs(libs.room.runtime)
    moshiLibs(libs.moshi.runtime)
    kspRunner(libs.ksp.aa.embeddable)

    kspThinRuntime(libs.ksp.aa)               // non-embeddable — KSP impl classes extracted, its bundled AA dropped
    kspThinRuntime(libs.ksp.api)
    kspThinRuntime(libs.ksp.common.deps)
    kspThinRuntime(project(":kotlin-compiler-deps")) // OUR unshaded compiler + Analysis API + IntelliJ platform
    kspThinRuntime(libs.kotlinx.coroutines.core)
    kspThinRuntime(libs.kotlin.stdlib)
}
// KSP2 stands up a full (relocated) Analysis API frontend in-process, the same heavyweight footprint as the
// K2 compiler tests in :lang-kotlin. Give the worker real heap and a fresh JVM per test class so the frontend
// footprint can't accumulate into an OutOfMemoryError across test classes.
tasks.named<Test>("test") {
    maxHeapSize = "3g"
    setForkEvery(1)
    // Hand the isolated Room + runner classpaths to the spikes / KspSourceGeneratorTest, resolved lazily at
    // execution time (so unrelated task graphs don't resolve them during configuration).
    doFirst {
        systemProperty("room.processor.classpath", roomProcessor.asPath)
        systemProperty("room.libs.classpath", roomLibs.asPath)
        systemProperty("moshi.libs.classpath", moshiLibs.asPath)
        systemProperty("ksp.runner.classpath", kspRunner.asPath)
        systemProperty("ksp.thin.runtime.classpath", kspThinRuntime.asPath)
    }
}
