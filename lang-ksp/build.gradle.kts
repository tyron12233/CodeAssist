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

    // ClassReader — read the method names a runtime class declares (KspProcessorCatalog's member-level
    // runtime floor) without loading it, the same way android-support/lang-java scan a classpath.
    implementation(libs.ow2.asm)

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

// Room's `room-compiler` pulls `org.xerial:sqlite-jdbc` (12.8 MB) for its compile-time SQL query verifier,
// which loads a native SQLite library. sqlite-jdbc has no build for Android/aarch64, and Room's
// `DatabaseVerifier` calls the native loader from a class static initializer with no fallback — so on device
// the real jar crashes the whole KSP run ("No native library found for os.name=Linux-Android"). We replace it
// in the Room bundle with a tiny native-free stub (`src/sqliteStub`, three `org.sqlite.*` classes): the
// verifier's static init then succeeds, its connection attempt throws a caught SQLException, and Room falls
// into its own `CANNOT_CREATE_VERIFICATION_DATABASE` path — generating the `_Impl` code (identical either way)
// without compile-time SQL verification. Also drops 12.8 MB from the APK.
/** The processor ids whose closure carries Room's sqlite-jdbc-backed query verifier. */
val sqliteVerifierProcessors = setOf("room", "room3")

val sqliteStub by sourceSets.creating
val sqliteStubJar by tasks.registering(Jar::class) {
    description = "Native-free org.sqlite stub that replaces sqlite-jdbc in the Room processor bundle (see src/sqliteStub)."
    archiveBaseName.set("sqlite-jdbc-stub")
    from(sqliteStub.output)
}

/** A resolvable config for [id]'s processor closure + a Zip packaging its (deduped) jars as /processors/<id>.zip. */
fun bundleProcessor(id: String, dep: Provider<*>) {
    val cfg = configurations.create("ksp_${id}_bundle")
    dependencies.add(cfg.name, dep)
    // com.intellij:annotations:12.0 (an ancient transitive of some processor closures, e.g. Room) and
    // org.jetbrains:annotations both define org.intellij.lang.annotations.* — packaging both makes D8 fail
    // "Duplicate class ...Identifier" when dexing the bundle on device. Drop the stale com.intellij one; the
    // org.jetbrains:annotations already in the closure supplies the same classes.
    cfg.exclude(group = "com.intellij", module = "annotations")
    // Room only (both generations — room3-compiler pulls the same sqlite-jdbc for the same verifier): drop
    // the native sqlite-jdbc and bundle the stub instead (see the note above).
    if (id in sqliteVerifierProcessors) cfg.exclude(group = "org.xerial", module = "sqlite-jdbc")
    val zip = tasks.register<Zip>("ksp${id.replaceFirstChar { it.uppercase() }}ProcessorZip") {
        description = "Packages the $id KSP processor closure as /processors/$id.zip (zip of jars; app-provided jars dropped)."
        archiveFileName.set("$id.zip")
        destinationDirectory.set(layout.buildDirectory.dir("generated/processors"))
        from(provider { cfg.filter { f -> f.name.endsWith(".jar") && appProvidedJarPrefixes.none { f.name.startsWith(it) } } })
        if (id in sqliteVerifierProcessors) from(sqliteStubJar)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    tasks.processResources { from(zip) { into("processors") } }
}

bundleProcessor("room", libs.room.compiler)
bundleProcessor("room3", libs.room3.compiler)
bundleProcessor("moshi", libs.moshi.kotlin.codegen)
bundleProcessor("hilt", libs.hilt.compiler)
bundleProcessor("glide", libs.glide.ksp)

// Room desktop spike (task 2): resolve Room as ISOLATED classpaths (NOT on the test compile classpath) so the
// spike loads room-compiler's SymbolProcessorProvider through a URLClassLoader+ServiceLoader — the real
// processor-loading path production takes — and feeds room-runtime as the KSP `libraries`. The paths are
// handed to the test as system properties. Self-gates (assumeTrue) when unresolvable.
val roomProcessor: Configuration by configurations.creating   // room-compiler + its processor closure
val roomLibs: Configuration by configurations.creating        // room-runtime/-common annotations + RoomDatabase
val room3Libs: Configuration by configurations.creating       // room3-runtime — the SEPARATE androidx.room3 group
val moshiLibs: Configuration by configurations.creating       // moshi runtime (JsonClass marker + KSerializer machinery)
val hiltLibs: Configuration by configurations.creating        // hilt-core (dagger.hilt.InstallIn marker) + the dagger runtime
// The Dagger the APP carries: it dexes bundletool for in-process .aab building, whose closure drags in an
// ANCIENT dagger runtime. HiltProcessorDaggerShadowingTest parents the processor loader on exactly that jar to
// reproduce the device classloader shape (see ToolClassIsolation). It is resolved from bundletool so the
// fixture tracks whatever version the app actually ships instead of a hardcoded guess.
val appProvidedDagger: Configuration by configurations.creating
// The guava the APP carries (bundletool's collections), which every processor bundle also ships at its own
// version. ProcessorGuavaShadowingTest parents the processor loader on it to reproduce the device classloader
// shape, where the app's copy is dexed at build time and the bundle's on device.
val appProvidedGuava: Configuration by configurations.creating
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
    room3Libs(libs.room3.runtime)
    moshiLibs(libs.moshi.runtime)
    hiltLibs(libs.hilt.core)
    appProvidedDagger(libs.android.bundletool)
    appProvidedGuava(libs.guava)   // the version :ide-android ships, so the fixture tracks the app
    kspRunner(libs.ksp.aa.embeddable)

    kspThinRuntime(libs.ksp.aa)               // non-embeddable — KSP impl classes extracted, its bundled AA dropped
    kspThinRuntime(libs.ksp.api)
    kspThinRuntime(libs.ksp.common.deps)
    kspThinRuntime(project(":kotlin-compiler-deps")) // OUR unshaded compiler + Analysis API + IntelliJ platform
    kspThinRuntime(libs.kotlinx.coroutines.core)
    kspThinRuntime(libs.kotlin.stdlib)
}
// KSP2 stands up a full (relocated) Analysis API frontend in-process, the same heavyweight footprint as the
// K2 compiler tests in :lang-kotlin, so the worker needs real heap. It does not need a fresh JVM per class:
// one shared worker amortizes the frontend/processor setup across the suite (measured: 22s → 8s wall, the
// same 22 tests green). If that footprint ever does accumulate into an OutOfMemoryError, set a middle
// `forkEvery(N)` rather than 1.
tasks.named<Test>("test") {
    maxHeapSize = "3g"
    dependsOn(sqliteStubJar)   // RoomWithoutSqliteJdbcTest swaps the stub in for the real sqlite-jdbc
    // Hand the isolated Room + runner classpaths to the spikes / KspSourceGeneratorTest, resolved lazily at
    // execution time (so unrelated task graphs don't resolve them during configuration).
    doFirst {
        systemProperty("room.processor.classpath", roomProcessor.asPath)
        systemProperty("room.libs.classpath", roomLibs.asPath)
        systemProperty("room3.libs.classpath", room3Libs.asPath)
        systemProperty("moshi.libs.classpath", moshiLibs.asPath)
        systemProperty("hilt.libs.classpath", hiltLibs.asPath)
        // Just the dagger runtime (+ the javax.inject it implements) out of bundletool's closure: what the
        // app's own classloader carries, and nothing else, so the fixture is the shadowing pair and not a
        // second copy of bundletool's whole world.
        systemProperty(
            "app.dagger.classpath",
            appProvidedDagger.filter { f ->
                (f.name.startsWith("dagger-") && !f.name.contains("compiler")) || f.name.startsWith("javax.inject")
            }.asPath,
        )
        // Just guava itself out of its closure: the fixture is the shadowing pair, not a second copy of the
        // app's whole collections world.
        systemProperty(
            "app.guava.classpath",
            appProvidedGuava.filter { f -> f.name.startsWith("guava-") }.asPath,
        )
        systemProperty("ksp.runner.classpath", kspRunner.asPath)
        systemProperty("ksp.thin.runtime.classpath", kspThinRuntime.asPath)
        systemProperty("sqlite.stub.jar", sqliteStubJar.get().archiveFile.get().asFile.absolutePath)
    }
}
