package dev.ide.android.support

import dev.ide.android.support.tasks.Aapt2CompileTask
import dev.ide.android.support.tasks.Aapt2LinkTask
import dev.ide.android.support.tasks.AndroidCompileTask
import dev.ide.android.support.tasks.AndroidKotlinCompileTask
import dev.ide.android.support.tasks.CheckAarMetadataTask
import dev.ide.android.support.tasks.ConvertResourcesTask
import dev.ide.android.support.tasks.DexArchiveBuilderTask
import dev.ide.android.support.tasks.DexExternalLibsTask
import dev.ide.android.support.tasks.DexMergeTask
import dev.ide.android.support.tasks.GenerateLibraryRTask
import dev.ide.android.support.tasks.GenerateRJarTask
import dev.ide.android.support.tasks.PackageAarTask
import dev.ide.android.support.tasks.GenerateViewBindingTask
import dev.ide.android.support.tasks.InjectAppLogProviderTask
import dev.ide.android.support.gms.GoogleServices
import dev.ide.android.support.tasks.BundleTask
import dev.ide.android.support.tasks.L8DexTask
import dev.ide.android.support.tasks.ManifestMergeTask
import dev.ide.android.support.tasks.MergeJavaResourcesTask
import dev.ide.android.support.tasks.MergeNativeLibsTask
import dev.ide.android.support.tasks.MergeResourcesTask
import dev.ide.android.support.tasks.PackageApkTask
import dev.ide.android.support.tasks.PackagingRules
import dev.ide.android.support.tasks.ProcessGoogleServicesTask
import dev.ide.android.support.tasks.R8MinifyTask
import dev.ide.android.support.tasks.SignApkTask
import dev.ide.android.support.tasks.SignBundleTask
import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.Aapt2Subprocess
import dev.ide.android.support.tools.AndroidAppLogRuntime
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.ApkSigner
import dev.ide.android.support.tools.ApkSignerTool
import dev.ide.android.support.tools.ApksigBundleSigner
import dev.ide.android.support.tools.ApksigSigner
import dev.ide.android.support.tools.BundleSigner
import dev.ide.android.support.tools.Bundler
import dev.ide.android.support.tools.BundletoolInProcess
import dev.ide.android.support.tools.D8Dexer
import dev.ide.android.support.tools.JarsignerBundleSigner
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.DesugarLib
import dev.ide.android.support.tools.DesugaredLibrary
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.R8InProcessShrinker
import dev.ide.android.support.tools.ResourceShrink
import dev.ide.android.support.tools.R8Subprocess
import dev.ide.android.support.tools.Shrinker
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.SyncResult
import dev.ide.build.Task
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.TaskContainer
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.JarTask
import dev.ide.build.engine.LifecycleTask
import dev.ide.build.engine.ProcessResourcesTask
import dev.ide.build.engine.jarPath
import dev.ide.build.jvm.JavaPlugin
import dev.ide.lang.kotlin.compile.BUILTIN_KOTLIN_COMPILER_PLUGINS
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.model.BuildSystemId
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.platform.ProgressReporter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The native, Gradle-free Android build system. It turns an
 * `android-app` module + a selected [dev.ide.android.support.AndroidVariant] into the incremental task
 * DAG `aapt2Compile -> aapt2Link(+R.java) -> compileJava -> dex -> packageApk -> sign`, run by
 * build-engine's generic [dev.ide.build.TaskExecutor]. Java/Kotlin compilation is owned by the language
 * modules (lang-jdt's ecj via [dev.ide.lang.jdt.compile.JdtBatchCompiler], lang-kotlin's K2 via
 * [IncrementalKotlinCompiler]), called directly by the Android compile tasks; the Android tools stay
 * injected ports defaulting to the SDK subprocess wirings.
 *
 * It shares [BuildSystemId.NATIVE] with the Java build system and distinguishes itself by [supports];
 * a host composes the two into one native build system that dispatches by module type.
 */
class AndroidBuildSystem(
    private val sdk: AndroidSdk,
    private val signing: SigningConfig,
    /**
     * The ecj/K2 `-bootclasspath`: empty on the desktop (ecj/kotlinc use the host JRE) and `android.jar` +
     * desugar stubs on ART (no host JRE to read). Host-specific, so the host supplies it. Distinct from
     * [compileBootclasspath], which separately puts `android.jar` on the regular compile `-classpath`.
     */
    private val bootClasspath: List<Path> = emptyList(),
    private val aapt2: Aapt2 = Aapt2Subprocess(sdk.aapt2),
    private val dexer: Dexer = D8Dexer(listOf(sdk.d8Jar), sdk.javaLauncher),
    /** Dexer for the dex MERGE step ([DexMergeTask]) specifically — the debug-path memory peak. Defaults to
     *  [dexer]; on ART the host injects a forked-VM D8 here so the merge gets a heap above the app cap, while
     *  the per-class archives keep using the in-process [dexer]. */
    private val mergeDexer: Dexer = dexer,
    private val shrinker: Shrinker = R8Subprocess(listOf(sdk.d8Jar), sdk.javaLauncher),
    private val signer: ApkSigner = ApkSignerTool(sdk.apksignerJar, sdk.zipalign, sdk.javaLauncher),
    /** Builds the `.aab` from a base module zip; in-process bundletool by default (it is not an SDK tool). */
    private val bundler: Bundler = BundletoolInProcess(),
    /** Signs the `.aab` (JAR/v1 only); the desktop default is `jarsigner`, the device wiring uses apksig. */
    private val bundleSigner: BundleSigner = JarsignerBundleSigner(sdk.jarsigner),
    private val kotlin: IncrementalKotlinCompiler? = null,
    /** Kotlin compiler plugins applied per module (the `platform.kotlinCompilerPlugin` EP contents; defaults
     *  to the built-ins). Shared with the [JavaPlugin] used for plain library modules in this project. */
    private val plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS,
    /** Global content-addressed library-dex cache (e.g. the host's shared caches dir); null = per-project only. */
    private val dexCacheRoot: Path? = null,
    /** Core-library-desugaring artifacts (desugar runtime + config jar); null = host ships none, so a module's
     *  `coreLibraryDesugaringEnabled` is a no-op. See [DesugarLib]. */
    private val desugarLib: DesugarLib? = null,
    /**
     * Resolves a build type's signing config from its `BuildType.signingConfig` reference (e.g. a keystore
     * registry id), given the app [Module] and the build-type name. Returns null to fall back to the default
     * [signing] (the debug keystore) — so an unassigned build type, or a missing/dangling reference, still
     * signs (debug) rather than failing. Null resolver ⇒ everything uses [signing] (the prior behavior).
     */
    private val signingResolver: ((Module, String) -> SigningConfig?)? = null,
    /** Max class-dex merged per batch when a debug scope is very large (the "Dex merge batch size" setting); a
     *  large app's merge is split into chunks of this size so its working set stays bounded ([DexMergeTask]).
     *  Read once per [createBuildGraph]; defaults to [DexMergeTask.DEFAULT_MERGE_CHUNK]. */
    private val mergeChunk: () -> Int = { DexMergeTask.DEFAULT_MERGE_CHUNK },
    /** The IDE log-bridge runtime woven into DEBUG builds (never release/minify). Evaluated per build graph so
     *  the host can gate it on a setting live; returns null (default) to not instrument — the build is then
     *  byte-identical to before. `:ide-android` supplies the bundled runtime when the "Forward app logs"
     *  setting is on. See [AndroidAppLogRuntime]. */
    private val appLogRuntime: () -> AndroidAppLogRuntime? = { null },
) : BuildSystem {

    override val id: BuildSystemId = BuildSystemId.NATIVE

    /**
     * The javac/ecj compile bootclasspath: `android.jar` plus the desugar stubs (`StringConcatFactory`,
     * `LambdaMetafactory`, …) when the build-tools ship them, so Java ≥ 9 string-concat and lambdas resolve
     * at compile time. Compile-only — these are never fed to D8/aapt2/the dexer (D8 desugars the indy), so
     * only the `compileJava` classpaths use this; `sdk.androidJar` stays the lone arg everywhere else.
     */
    private val compileBootclasspath: List<Path> =
        listOf(sdk.androidJar) + listOfNotNull(sdk.coreLambdaStubs.takeIf { Files.exists(it) })

    override suspend fun sync(project: Project, progress: ProgressReporter): SyncResult = SyncResult(true, emptyList())

    override fun supports(moduleType: ModuleType): Boolean = moduleType.id.startsWith("android")

    override fun tasks(project: Project): List<TaskDescriptor> =
        project.modules.filter { supports(it.type) }.flatMap { m ->
            val isApp = m.type.id == "android-app"
            AndroidVariants.compute(m).flatMap {
                listOfNotNull(
                    // Apps assemble an APK; libraries assemble an .aar.
                    if (isApp) TaskDescriptor("assemble${it.name.cap()}", "build", "Assemble the ${it.name} APK of :${m.name}")
                    else TaskDescriptor("assembleAar${it.name.cap()}", "build", "Assemble the ${it.name} AAR of :${m.name}"),
                    // Only app modules produce an .aab (a library has no application bundle).
                    TaskDescriptor("bundle${it.name.cap()}", "build", "Bundle the ${it.name} AAB of :${m.name}")
                        .takeIf { _ -> isApp },
                )
            }
        }.distinctBy { it.name }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val targets = (if (request.targets.isEmpty()) project.modules.map { it.id } else request.targets)
            .mapNotNull { byId[it] }
            .filter { supports(it.type) }

        // One configuration phase: the Java plugin contributes each plain library module's tasks
        // (compileJava/processResources/classes/jar); the android tasks are added here and wired to the
        // Java tasks by name (e.g. dexBuilderLib dependsOn `:lib:jar`). The container realizes it at build().
        val tasks = DefaultTaskContainer()
        // Plain library modules pulled into an Android build compile against the Android platform (android.jar)
        // like the app itself — the same boot classpath for every module in this graph.
        val javaPlugin = JavaPlugin({ bootClasspath }, kotlin, plugins)
        val withJar = request.goal != BuildGoal.COMPILE_ONLY
        val registered = HashSet<ModuleId>()
        for (target in targets) {
            val variant = AndroidVariants.select(target, request.variant.name)
                ?: AndroidVariants.defaultVariant(target) ?: continue
            val targetFacet = target.facets.get(AndroidFacet.KEY) ?: continue
            for (m in moduleClosure(target, byId)) {
                if (!registered.add(m.id)) continue
                if (m.facets.get(AndroidFacet.KEY) != null) registerAndroidLibrary(tasks, m, byId, withJar, variant, targetFacet)
                else javaPlugin.registerModule(tasks, m, byId, withJar)   // reuse the Java plugin
            }
            // An `android-lib` TARGET packages an .aar (assembleAar); an `android-app` builds the APK/AAB.
            if (target.type.id == "android-lib") {
                // The library target isn't in its own moduleClosure — register its build tasks, then the AAR.
                if (registered.add(target.id)) registerAndroidLibrary(tasks, target, byId, withJar = true, variant, targetFacet)
                appendAar(tasks, target, variant, targetFacet)
            } else {
                appendApp(tasks, target, variant, request.goal, byId)
            }
        }
        return tasks.build()
    }

    /** Register a task with its (name-based) hard dependencies; the factory runs lazily at realize. */
    private fun TaskContainer.task(name: TaskName, deps: List<TaskName> = emptyList(), create: () -> Task) {
        register(name, create).configure { if (deps.isNotEmpty()) dependsOn(*deps.toTypedArray()) }
    }

    private fun appendApp(tasks: TaskContainer, app: Module, variant: AndroidVariant, goal: BuildGoal, byId: Map<ModuleId, Module>) {
        val facet = app.facets.get(AndroidFacet.KEY) ?: return
        val layout = Layout(app, variant.name)
        val v = variant.name.cap()
        fun step(s: String) = TaskName(":${app.name}:$s$v")

        val libs = AndroidLibraries.resolve(app, layout.explodedAar, variant.configurations)
        val appModuleOutputs = app.classpath(DependencyScope.IMPLEMENTATION, variant = variant.configurations).entries
            .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }

        val closure = moduleClosure(app, byId)
        val depAndroidLibs = closure.filter { it.facets.get(AndroidFacet.KEY) != null }
        // R is generated for every dependency-lib package AND every external AAR package (so an AAR's own
        // classes + custom-view attrs resolve against the app-linked resource table).
        val extraPackages = (depAndroidLibs.mapNotNull { it.facets.get(AndroidFacet.KEY)?.namespace } +
            libs.aarPackages).distinct()

        // Firebase/Play Services: a google-services.json in the module generates string resources merged
        // into the app's res (below the app's own res, so the app can still override). Located AGP-style.
        val gmsJson = GoogleServices.findJson(layout.moduleDir, variant)
        val gmsRes = if (gmsJson != null) listOf(layout.generatedGmsRes) else emptyList()

        // A dependency lib contributes the resources/assets of ITS matching variant (build-type-first), not
        // all its source sets, so a debug-only or flavor-only resource doesn't leak into the wrong variant.
        fun depRoots(dep: Module, role: ContentRole): List<Path> =
            AndroidVariants.matchLibraryVariant(dep, variant, facet)?.let { roots(it, role) } ?: moduleRoots(dep, role)
        val mergeResInputs = depAndroidLibs.flatMap { depRoots(it, ContentRole.ANDROID_RES) } +
            libs.resDirs + gmsRes + roots(variant, ContentRole.ANDROID_RES)
        val sourceRoots = roots(variant, ContentRole.SOURCE)
        val assetsDirs = depAndroidLibs.flatMap { depRoots(it, ContentRole.ASSETS) } +
            libs.assetsDirs + roots(variant, ContentRole.ASSETS)
        val level = levelOf(app.languageLevel)
        val bt = facet.buildType(variant.buildTypeName)
        val release = bt?.debuggable == false
        val minify = bt?.minifyEnabled == true
        // shrinkResources requires minify (R8's reachable-code analysis drives it); ignored otherwise (AGP errors).
        val shrinkResources = minify && bt.shrinkResources
        // Debug-only IDE log bridge: on a debuggable, non-minified build the host-supplied runtime is woven in
        // (its ContentProvider registered in the manifest + its classes added to the external dex scope), so
        // the running app forwards its logs back to the IDE. Never touches release/minify builds; the DEX goal
        // (layout-preview dex-prepare) is excluded so its dex-bucket seeding stays byte-identical.
        val appLog = appLogRuntime()?.takeIf { bt?.debuggable == true && !minify && goal != BuildGoal.DEX }
        // An app bundle (.aab) is built from PROTO resources, so force proto linking for the bundle goal too.
        val bundle = goal == BuildGoal.BUNDLE
        val protoResources = shrinkResources || bundle

        // versionName composed as AGP does: flavor override (else defaultConfig) + build-type suffix.
        val flavorVersionName = variant.flavorNames.firstNotNullOfOrNull { fn ->
            facet.productFlavors.firstOrNull { it.name == fn }?.versionName
        }
        val versionName = (flavorVersionName ?: facet.versionName) +
            (facet.buildType(variant.buildTypeName)?.versionNameSuffix ?: "")
        val versionCode = facet.versionCode

        // applicationId (AGP: flavor override else namespace, + flavor & build-type suffixes) — the value of
        // the ${applicationId} manifest placeholder Firebase/Play Services authorities depend on.
        val flavorAppId = variant.flavorNames.firstNotNullOfOrNull { fn ->
            facet.productFlavors.firstOrNull { it.name == fn }?.applicationId
        }
        val flavorIdSuffix = variant.flavorNames.mapNotNull { fn ->
            facet.productFlavors.firstOrNull { it.name == fn }?.applicationIdSuffix
        }.joinToString("")
        val applicationId = (flavorAppId ?: facet.namespace) + flavorIdSuffix + (bt?.applicationIdSuffix ?: "")
        val manifestPlaceholders = mapOf("applicationId" to applicationId, "packageName" to facet.namespace)
        // Library manifests to merge, in decreasing priority: local android-lib modules, then external AARs.
        val depLibManifests = depAndroidLibs.mapNotNull { lib ->
            val libFacet = lib.facets.get(AndroidFacet.KEY) ?: return@mapNotNull null
            Paths.get(lib.outputDir.path).parent.parent.resolve(libFacet.manifest).takeIf { Files.exists(it) }
        }
        val libraryManifests = depLibManifests + libs.aarManifests

        val directDepCompiles = directModuleDeps(app, byId).map { TaskName(":${it.name}:compileJava") }

        // Kotlin: upstream modules' `kotlin-classes` (sibling of their Java output) join the compile classpath;
        // when the app itself has `.kt`, its own `compileKotlin` runs first and its output is added too.
        val upstreamKotlin = appModuleOutputs.map { it.resolveSibling("kotlin-classes") }
        val appHasKotlin = kotlin != null && containsKotlin(sourceRoots)
        val directDepKotlin = directModuleDeps(app, byId).filter { moduleHasKotlin(it) }
            .map { TaskName(":${it.name}:compileKotlin") }
        val kotlinClasspath = compileBootclasspath + libs.compileJars + appModuleOutputs + upstreamKotlin
        // The Java compile resolves `R` from the generated R.jar (Kotlin keeps resolving it from `R.java` source).
        val compileClasspath = kotlinClasspath + listOf(layout.rJar) + if (appHasKotlin) listOf(layout.kotlinClasses) else emptyList()

        val mergeRes = step("mergeResources")
        val processManifest = step("processManifest")
        val aapt2Compile = step("aapt2Compile")
        val aapt2Link = step("aapt2Link")
        val generateRFile = step("generateRFile")
        val compileKotlin = step("compileKotlin")
        val compile = step("compileJava")
        // buildFeatures { viewBinding }: generate <Layout>Binding.java from this module's own layouts, fed to
        // both compile tasks as an extra source root. Independent of R generation (it only references R), but
        // the compile tasks already depend on aapt2Link so R exists when this generated code is compiled.
        val viewBinding = if (facet.buildFeatures.viewBinding) step("generateViewBinding") else null
        if (viewBinding != null) {
            tasks.task(viewBinding) {
                GenerateViewBindingTask(viewBinding, roots(variant, ContentRole.ANDROID_RES), facet.namespace, layout.viewBindingGen)
            }
        }
        val vbGenDirs = listOfNotNull(viewBinding).map { layout.viewBindingGen }
        val vbDep = listOfNotNull(viewBinding)

        // google-services.json (when present) generates res that the merge consumes, so it runs first.
        val mergeResDeps = if (gmsJson != null) {
            val processGms = step("processGoogleServices")
            tasks.task(processGms) {
                ProcessGoogleServicesTask(processGms, gmsJson, applicationId, facet.namespace, layout.generatedGmsRes)
            }
            listOf(processGms)
        } else emptyList()
        tasks.task(mergeRes, mergeResDeps) { MergeResourcesTask(mergeRes, mergeResInputs, layout.mergedRes) }
        // Fail early (before compilation) if a dependency AAR requires a higher compileSdk than the app has
        // (AGP's checkAarMetadata). Gates processManifest so a violation stops the build with a clear message.
        val checkAarMeta = step("checkAarMetadata")
        tasks.task(checkAarMeta) {
            CheckAarMetadataTask(checkAarMeta, libs.aarMetadata, facet.compileSdk, layout.aarMetadataCheck)
        }
        // Merge dependency-library + AAR manifests into the app manifest (so their components/permissions
        // land in the APK), substituting ${applicationId} etc. The linked manifest is the merged one.
        tasks.task(processManifest, listOf(checkAarMeta)) {
            ManifestMergeTask(processManifest, layout.manifest(facet), libraryManifests, manifestPlaceholders, facet.minSdk, facet.targetSdk, layout.mergedManifest)
        }
        // On a debug build, splice the log-bridge <provider> into the merged manifest before linking; aapt2
        // then links the instrumented copy. Non-debug builds link the plain merged manifest directly.
        val linkManifest: Path
        val manifestDep: TaskName
        if (appLog != null) {
            val injectAppLogTask = step("injectAppLogProvider")
            val authority = "$applicationId.${appLog.authoritySuffix}"
            tasks.task(injectAppLogTask, listOf(processManifest)) {
                InjectAppLogProviderTask(injectAppLogTask, layout.mergedManifest, appLog.providerClassName, authority, layout.instrumentedManifest)
            }
            linkManifest = layout.instrumentedManifest
            manifestDep = injectAppLogTask
        } else {
            linkManifest = layout.mergedManifest
            manifestDep = processManifest
        }
        tasks.task(aapt2Compile, listOf(mergeRes)) { Aapt2CompileTask(aapt2Compile, listOf(layout.mergedRes), layout.compiledRes, aapt2) }
        // A minify build needs aapt2's manifest/layout keep rules so R8 does not strip XML-referenced classes;
        // a shrinkResources build additionally links proto resources (R8's resource-shrinker input form).
        tasks.task(aapt2Link, listOf(aapt2Compile, manifestDep)) {
            Aapt2LinkTask(
                aapt2Link,
                layout.compiledRes,
                linkManifest,
                sdk.androidJar,
                facet.namespace,
                extraPackages,
                facet.minSdk,
                facet.targetSdk,
                versionCode,
                versionName,
                layout.genJava,
                if (protoResources) layout.protoAp else layout.resourcesAp,
                aapt2,
                proguardRules = if (minify) layout.aaptProguardRules else null,
                protoFormat = protoResources,
            )
        }
        // Package the generated R.java (app + every --extra-package) as R.jar bytecode instead of compiling it.
        tasks.task(generateRFile, listOf(aapt2Link)) { GenerateRJarTask(generateRFile, layout.genJava, layout.rJar) }
        if (appHasKotlin) {
            tasks.task(compileKotlin, listOf(aapt2Link) + directDepCompiles + directDepKotlin + vbDep) {
                AndroidKotlinCompileTask(
                    app,
                    compileKotlin,
                    sourceRoots,
                    layout.genJava,
                    kotlinClasspath,
                    layout.kotlinClasses,
                    level,
                    bootClasspath,
                    kotlin,
                    plugins,
                    extraGenDirs = vbGenDirs,
                )
            }
        }

        // The app compiles its own sources + ViewBinding; `R` arrives as R.jar (from generateRFile) on the
        // classpath, not as compiled source. Non-R generated files (e.g. Manifest.java) are still compiled.
        tasks.task(
            compile,
            listOf(aapt2Link, generateRFile) + directDepCompiles + (if (appHasKotlin) listOf(compileKotlin) else emptyList()) + vbDep
        ) {
            AndroidCompileTask(
                compile,
                sourceRoots,
                layout.genJava,
                compileClasspath,
                layout.classes,
                level,
                bootClasspath,
                extraGenDirs = vbGenDirs,
            )
        }
        // The app's project-scope dex covers both the Java and (when present) the Kotlin output.
        val appProjectClasses = listOf(layout.classes) + if (appHasKotlin) listOf(layout.kotlinClasses) else emptyList()

        if (goal == BuildGoal.COMPILE_ONLY) return

        val pkg = step("packageApk")
        val sign = step("sign")

        // Inputs to dex, by AGP scope: sub-module `jar` artifacts (consumed BY NAME) and external libraries.
        val subProjectJars = closure.map { jarPath(it) }
        val moduleJarProducers = closure.map { TaskName(":${it.name}:jar") }
        // The debug-only log-bridge runtime rides the external dex scope (its immutable jar is content-hashed,
        // so it's dexed once and cached like any library); null on release/non-instrumented builds.
        val externalJars = libs.dexJars + (appLog?.let { listOf(it.runtimeJar) } ?: emptyList())
        // The app's R.jar (generateRFile): dexed in its OWN scope (rArchives) and merged into the PROJECT dex layer,
        // where AGP keeps R — NOT the external scope. Content-hashed, so it re-dexes only when resources change,
        // and being out of the external scope means a resource edit never re-dexes or re-merges the stable libraries.
        val rJars = listOf(layout.rJar)

        // Packaging merges (AGP's merge<Variant>NativeLibs / merge<Variant>JavaResource). Native libs come from
        // the app's own `src/*/jniLibs`, each dep android-lib's jniLibs, exploded-AAR `jni/`, and `lib/<abi>/*.so`
        // inside external jars; ordered app-first so a pickFirst duplicate keeps the app's copy. Java resources
        // come from `src/*/resources` (app + dep libs) and the non-class entries of the sub-module + external jars.
        val mergeNativeLibs = step("mergeNativeLibs")
        val mergeJavaRes = step("mergeJavaResource")
        val jniDirs = roots(variant, ContentRole.JNI_LIBS) +
            depAndroidLibs.flatMap { depRoots(it, ContentRole.JNI_LIBS) } + libs.jniLibDirs
        val javaResDirs = roots(variant, ContentRole.RESOURCE) +
            depAndroidLibs.flatMap { depRoots(it, ContentRole.RESOURCE) }
        val nativeLibsFilter = PackagingRules.jniLibsFilter(facet.packaging.jniLibs)
        val javaResFilter = PackagingRules.resourceFilter(facet.packaging.resources)
        // externalJars are static (resolved on disk); only the sub-module jars need building first.
        tasks.task(mergeNativeLibs) {
            MergeNativeLibsTask(mergeNativeLibs, jniDirs, externalJars, nativeLibsFilter, layout.mergedNativeLibs)
        }
        tasks.task(mergeJavaRes, moduleJarProducers) {
            MergeJavaResourcesTask(mergeJavaRes, javaResDirs, subProjectJars + externalJars, javaResFilter, layout.mergedJavaRes)
        }
        val packagingDeps = listOf(mergeNativeLibs, mergeJavaRes)

        // Core-library desugaring: extract the config when enabled and the host ships the artifacts. The R8
        // (minify) path emits L8 keep rules and shrinks the runtime; the D8 (debug) path keeps the whole runtime.
        val desugaring = if (facet.coreLibraryDesugaringEnabled) desugarLib else null
        val desugarJson = desugaring?.extractConfigJson(layout.desugarConfigJson)

        // Fast external-library dexing: when desugaring applies (the per-lib archive cache is already whole-set
        // keyed, so per-lib buckets buy no incrementality — see DexExternalLibsTask), dex the whole external
        // classpath to indexed dex in ONE forked big-heap pass instead of per-lib archive + merge (~2.6x faster
        // fresh on a high-RAM device; self-falls-back to in-process). minSdk >= 26 (no desugaring) keeps per-lib
        // buckets for cross-project per-library reuse. Native multidex only (mono-dex merges everything as one).
        //
        // EXCEPT the `prepareDex` (BuildGoal.DEX) goal: it exists only to seed the layout preview's per-library dex
        // buckets (`SharedLibraryDexer`), which the readiness gate + real-view render read. The one-pass path writes
        // an `ext-indexed` MERGED dex instead of those buckets, so with it the gate never flips — a minSdk 21-25
        // project would show "prepare libraries" forever even after a successful prepare. Force the per-lib archive
        // path for DEX so prepare seeds exactly what the preview consumes; the APK build keeps the faster one-pass.
        //
        // AND only when the merge dexer runs OFF the app heap ([Dexer.runsOffHeap]): the one-pass is a single
        // monolithic D8 program over the whole classpath — a big win in a forked/subprocess VM's large heap, but
        // pathological in-process on a low-memory device (GC-bound, hundreds of seconds, killable before it
        // caches). When the on-device forked dexer has fallen back to in-process, drop to bounded per-library
        // archiving (the !dexExtOnePass branch): each library dexes with a capped working set and banks to the
        // shared cache as it completes, so progress survives a low-memory-killer stop.
        // Keyed on the REAL external libraries (libs.dexJars), not the log-bridge runtime we may have appended:
        // a dep-less app shouldn't flip to the forked one-pass just because instrumentation added one tiny jar.
        val dexExtOnePass =
            facet.minSdk in 21..25 && libs.dexJars.isNotEmpty() && !minify && goal != BuildGoal.DEX && mergeDexer.runsOffHeap()

        // The merged dex layers the packager assembles (renumbered into one classes*.dex set) + packageApk's deps.
        var dexDirs: List<Path>
        var pkgDeps: List<TaskName>

        if (minify) {
            // Release: R8 shrinks + optimizes + obfuscates + dexes app classes + every library jar in one pass.
            // Keep-rule sources, in AGP order: aapt2's manifest/layout rules, then the build type's
            // proguardFiles (bundled defaults + module files), then dependency-lib + AAR consumer rules.
            val appProguard = resolveProguardFiles(bt.proguardFiles, layout.moduleDir, layout.proguardDefaults)
            val depConsumer = depAndroidLibs.flatMap { lib ->
                val libDir = Paths.get(lib.outputDir.path).parent.parent
                val libBt = lib.facets.get(AndroidFacet.KEY)?.buildType?.invoke(variant.buildTypeName)
                resolveProguardFiles(libBt?.consumerProguardFiles ?: emptyList(), libDir, layout.proguardDefaults)
            }
            val keepRuleFiles = listOf(layout.aaptProguardRules) + appProguard + depConsumer + libs.consumerProguardFiles
            val inlineRules = bt.proguardRules
            val resourceShrink = if (shrinkResources) ResourceShrink(layout.protoAp, layout.shrunkProtoAp) else null

            val minifyTask = TaskName(":${app.name}:minify${v}WithR8")
            tasks.task(minifyTask, listOf(aapt2Link, generateRFile, compile) + moduleJarProducers) {
                R8MinifyTask(
                    minifyTask, appProjectClasses + subProjectJars + externalJars + rJars, sdk.androidJar, facet.minSdk,
                    keepRuleFiles, inlineRules, facet.r8FullMode,
                    layout.dexArchives.resolve("r8-staging"), layout.dex, shrinker,
                    mappingOutput = layout.mappingTxt,
                    resources = resourceShrink,
                    desugaredLibrary = desugarJson?.let { DesugaredLibrary(it, layout.desugarKeepRules) },
                )
            }
            dexDirs = listOf(layout.dex)
            if (shrinkResources && !bundle) {
                // R8 emitted shrunk PROTO resources; convert them back to binary for APK packaging. A bundle
                // keeps the proto form (it consumes shrunkProtoAp directly), so skip the conversion there.
                val shrinkRes = TaskName(":${app.name}:shrinkResources$v")
                tasks.task(shrinkRes, listOf(minifyTask)) {
                    ConvertResourcesTask(shrinkRes, layout.shrunkProtoAp, layout.protoAp, layout.resourcesAp, aapt2)
                }
                pkgDeps = listOf(shrinkRes, minifyTask)
            } else {
                pkgDeps = listOf(aapt2Link, minifyTask)
            }
        } else {
            // Debug: ONE dexBuilder archives each scope (per-class, content-addressed, internally incremental);
            // the scope merges combine the archives into indexed dex. AGP names: dexBuilder → mergeProjectDex /
            // mergeLibDex / mergeExtDex (native multidex), or → mergeDex (MERGE_ALL) for mono/legacy multidex.
            // When forking the external classpath, R.jar is ALSO routed through a forked one-pass ([dexRDex],
            // its own dex layer) rather than archived in dexBuilder + merged into the project layer: R is ~50
            // dependency-package classes that dex GC-bound + single-threaded in-process (~5s), so the big-heap
            // fork helps it too. Otherwise R stays in dexBuilder + the project merge (AGP's scope for R).
            val forkedR = dexExtOnePass
            val dexBuilder = step("dexBuilder")
            tasks.task(dexBuilder, listOf(compile, generateRFile) + moduleJarProducers) {
                DexArchiveBuilderTask(dexBuilder, appProjectClasses, subProjectJars, externalJars, sdk.androidJar,
                    facet.minSdk, release, layout.dexArchives.resolve("project.jar"),
                    layout.projectArchives, layout.subArchives, layout.extArchives, dexer, dexCacheRoot,
                    desugaredLibConfig = desugarJson,
                    rJars = if (forkedR) emptyList() else rJars, rDexRoot = if (forkedR) null else layout.rArchives,
                    // When one-passing the external classpath, keep ext libs on the desugaring classpath here but
                    // don't archive them — DexExternalLibsTask dexes them to indexed dex directly.
                    archiveExternalScope = !dexExtOnePass)
            }
            // The "Dex merge batch size" setting, read once for this build (chunks a very large scope merge).
            val mergeBatch = mergeChunk()
            if (facet.minSdk >= 21) {
                // Native multidex: ART loads many dex, so keep the scopes split for the best incrementality.
                val mergeProjectDex = step("mergeProjectDex")
                // R.jar merges into the PROJECT layer (AGP's scope for R) unless it's forked into its own layer.
                val projectMergeInputs = if (forkedR) listOf(layout.projectArchives) else listOf(layout.projectArchives, layout.rArchives)
                tasks.task(mergeProjectDex, listOf(dexBuilder)) {
                    DexMergeTask(mergeProjectDex, projectMergeInputs, sdk.androidJar, facet.minSdk, release, layout.projectDex, mergeDexer, mergeChunk = mergeBatch)
                }
                val dirs = arrayListOf(layout.projectDex)
                val deps = arrayListOf(aapt2Link, mergeProjectDex)
                if (subProjectJars.isNotEmpty()) {
                    val mergeLibDex = step("mergeLibDex")
                    tasks.task(mergeLibDex, listOf(dexBuilder)) {
                        DexMergeTask(mergeLibDex, listOf(layout.subArchives), sdk.androidJar, facet.minSdk, release, layout.libDex, mergeDexer, mergeChunk = mergeBatch)
                    }
                    dirs.add(layout.libDex); deps.add(mergeLibDex)
                }
                if (externalJars.isNotEmpty() && dexExtOnePass) {
                    // One forked big-heap pass: whole external classpath → indexed dex, content-addressed by the
                    // library set. Sequenced AFTER the in-process dex tasks (dexBuilder + the project/lib merges;
                    // not a data dep — it reads the library jars): the forked VM and the in-process dexers both
                    // want all cores + heap, so overlapping them makes BOTH slower. Run alone, it gets the full
                    // machine (~6s vs ~15s contended).
                    val dexExtLibs = step("dexExtLibs")
                    tasks.task(dexExtLibs, deps.toList()) {
                        DexExternalLibsTask(dexExtLibs, externalJars, sdk.androidJar, facet.minSdk, release, layout.extDex, mergeDexer, desugarJson, dexCacheRoot?.resolve("ext-indexed"))
                    }
                    dirs.add(layout.extDex); deps.add(dexExtLibs)
                }
                if (forkedR) {
                    // R.jar → its own indexed dex layer + cache namespace via the forked big-heap one-pass (so a
                    // resource edit re-dexes only R, never the 61 libs). Sequenced after the other dex tasks (incl.
                    // dexExtLibs) so the forked VMs don't overlap and contend. The fork wins even for R's classes:
                    // D8 in-process is GC-bound on ART's ~576MB app heap (measured 5.9s in-process vs 3.1s forked),
                    // and that holds whether or not android.jar is loaded — it's D8's own working set, not the
                    // library. R is the app's FINAL R for every lib package (--extra-packages), so it is not tiny.
                    val dexRDex = step("dexRDex")
                    tasks.task(dexRDex, listOf(generateRFile) + deps.toList()) {
                        DexExternalLibsTask(dexRDex, rJars, sdk.androidJar, facet.minSdk, release, layout.rDex, mergeDexer, desugarJson, dexCacheRoot?.resolve("r-indexed"))
                    }
                    dirs.add(layout.rDex); deps.add(dexRDex)
                }
                if (externalJars.isNotEmpty() && !dexExtOnePass) {
                    val mergeExtDex = step("mergeExtDex")
                    // Below AGP's LIBRARIES_MERGING_THRESHOLD: merge per-library (more dex files, finer isolation).
                    val perLib = externalJars.size <= extMergeThreshold(facet.minSdk)
                    tasks.task(mergeExtDex, listOf(dexBuilder)) {
                        // External libs are immutable + pinned, so their MERGED dex is content-addressable and
                        // reused across builds/cleans/projects (mergeCacheRoot) — not just the per-lib archives.
                        DexMergeTask(mergeExtDex, listOf(layout.extArchives), sdk.androidJar, facet.minSdk, release, layout.extDex, mergeDexer, groupPerBucket = perLib, mergeChunk = mergeBatch, mergeCacheRoot = dexCacheRoot?.resolve("merged-ext"))
                    }
                    dirs.add(layout.extDex); deps.add(mergeExtDex)
                }
                dexDirs = dirs; pkgDeps = deps
            } else {
                // Mono-/legacy-multidex (minSdk < 21): merge every scope into a single classes.dex set.
                val mergeDex = step("mergeDex")
                tasks.task(mergeDex, listOf(dexBuilder)) {
                    DexMergeTask(mergeDex, listOf(layout.projectArchives, layout.subArchives, layout.extArchives, layout.rArchives),
                        sdk.androidJar, facet.minSdk, release, layout.dex, mergeDexer, mergeChunk = mergeBatch)
                }
                dexDirs = listOf(layout.dex); pkgDeps = listOf(aapt2Link, mergeDex)
            }
        }

        // Dex-only goal (prepare the layout preview): the `dexBuilder`/scope-merge tasks above have populated the
        // shared library-dex cache — stop here, before L8 / packaging / signing. This is what the preview's
        // "prepare libraries" action runs so the (one-time, expensive) library dexing happens as an explicit
        // build, not silently inside a preview render.
        if (goal == BuildGoal.DEX) return

        // Core-library desugaring runtime (L8): dex `desugar_jdk_libs` into its own layer, packaged alongside
        // the app dex. We keep the WHOLE runtime (L8 keep-all) rather than shrinking it to the app's used APIs:
        // L8 release-shrinking against R8's emitted keep rules drops internal `j$.util.*Conversions` helpers
        // that surviving classes still reference ("Missing class"). Keeping all is correct and only slightly
        // larger. It needs only the (config-time) desugar config + runtime jar, so it has no task dependency.
        if (desugarJson != null && desugaring != null) {
            val l8 = step("l8DexDesugarLib")
            tasks.task(l8) {
                L8DexTask(
                    l8, desugaring.runtimeJar, desugarJson, layout.desugarKeepRules, sdk.androidJar,
                    facet.minSdk, release = false, layout.desugarLibDex, shrinker,
                )
            }
            dexDirs = dexDirs + listOf(layout.desugarLibDex)
            pkgDeps = pkgDeps + listOf(l8)
        }

        // The packaging step also waits on the native-lib + Java-resource merges (common to both the APK and
        // bundle terminals, and to the minify + debug dex paths).
        pkgDeps = pkgDeps + packagingDeps

        // The keystore that signs this variant: the build type's assigned signing config (release keystore),
        // or the default debug keystore when unassigned / dangling. Resolved once for both APK and bundle.
        val variantSigning = signingFor(app, variant.buildTypeName)

        if (bundle) {
            // Bundle terminal (AGP's `bundle<Variant>`): build the base module zip from the PROTO resources +
            // dex + assets + jni, run bundletool, then JAR-sign the .aab. Reuses the same dex layers as the APK.
            val bundleResAp = if (shrinkResources) layout.shrunkProtoAp else layout.protoAp
            val packageBundle = step("packageBundle")
            tasks.task(packageBundle, pkgDeps) {
                BundleTask(packageBundle, bundleResAp, dexDirs, assetsDirs, listOf(layout.mergedNativeLibs), layout.unsignedAab, layout.baseModuleZip, bundler, javaResJars = listOf(layout.mergedJavaRes))
            }
            val signBundle = step("signBundle")
            tasks.task(signBundle, listOf(packageBundle)) {
                SignBundleTask(signBundle, layout.unsignedAab, layout.signedAab, variantSigning, facet.minSdk, bundleSigner)
            }
            val bundleLifecycle = step("bundle")
            tasks.task(bundleLifecycle, listOf(signBundle)) { LifecycleTask(bundleLifecycle, trackedFiles = listOf(layout.signedAab)) }
            return
        }

        tasks.task(pkg, pkgDeps) { PackageApkTask(pkg, layout.resourcesAp, dexDirs, assetsDirs, listOf(layout.mergedNativeLibs), layout.unsignedApk, javaResJars = listOf(layout.mergedJavaRes)) }
        tasks.task(sign, listOf(pkg)) { SignApkTask(sign, layout.unsignedApk, layout.signedApk, variantSigning, signer) }
        // Top-level lifecycle aggregate (AGP's `assemble<Variant>`): fronts the signed APK.
        val assemble = step("assemble")
        tasks.task(assemble, listOf(sign)) { LifecycleTask(assemble, trackedFiles = listOf(layout.signedApk)) }
    }

    /**
     * Register an `android-lib` dependency's tasks: an android compile against `android.jar` + its own
     * non-final R (decoupled, compile-only — `generateR → compileR`, kept OUT of the dexed output), plus the
     * shared `processResources`/`classes`/`jar` lifecycle from the Java plugin. A plain `java-lib` goes
     * straight through [JavaPlugin.registerModule]; this is the android variant of that.
     */
    private fun registerAndroidLibrary(tasks: TaskContainer, m: Module, byId: Map<ModuleId, Module>, withJar: Boolean, consumerVariant: AndroidVariant, consumerFacet: AndroidFacet) {
        val classesOut = Paths.get(m.outputDir.path)
        val buildDir = classesOut.parent
        // Build this dependency lib in the variant matching the app being assembled (build-type + dimension-aware
        // flavor match); its variant-scoped source sets, resources, R and library deps are used, not all sets.
        val libVariant = AndroidVariants.matchLibraryVariant(m, consumerVariant, consumerFacet)
        val configs = libVariant?.configurations
        fun srcRoots(role: ContentRole): List<Path> = libVariant?.let { roots(it, role) } ?: moduleRoots(m, role)
        val libs = AndroidLibraries.resolve(m, buildDir.resolve("intermediates").resolve("exploded-aar"), configs)
        val moduleOutputs = m.classpath(DependencyScope.IMPLEMENTATION, variant = configs).entries
            .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }
        val level = levelOf(m.languageLevel)
        val facet = m.facets.get(AndroidFacet.KEY)!!
        // Upstream modules' Kotlin output (sibling of their Java output) joins this lib's compile classpath.
        val upstreamKotlin = moduleOutputs.map { it.resolveSibling("kotlin-classes") }
        val classpath = ArrayList(compileBootclasspath + libs.compileJars + moduleOutputs + upstreamKotlin)
        val compileDeps = directModuleDeps(m, byId).map { TaskName(":${it.name}:compileJava") }.toMutableList()
        val sourceRoots = srcRoots(ContentRole.SOURCE)

        val rRoot = buildDir.resolve("intermediates").resolve("r")
        val generateR = TaskName(":${m.name}:generateR")
        val compileR = TaskName(":${m.name}:compileR")
        tasks.task(generateR) {
            GenerateLibraryRTask(
                generateR,
                srcRoots(ContentRole.ANDROID_RES),
                buildDir.parent.resolve(facet.manifest),
                sdk.androidJar,
                facet.namespace,
                facet.minSdk,
                rRoot.resolve("res"),
                rRoot.resolve("gen"),
                rRoot.resolve("lib.ap_"),
                rRoot.resolve("AndroidManifest.xml"),
                aapt2,
                rTxt = rRoot.resolve("R.txt"),   // the symbol table an AAR ships (assembleAar reads it)
            )
        }
        // The lib's own (non-final) R as R.jar bytecode — compile-only, kept OUT of the dexed output, so the
        // app's final R wins at runtime. Same R.jar artifact shape as the app, generated not compiled.
        val rJar = rRoot.resolve("R.jar")
        tasks.task(compileR, listOf(generateR)) { GenerateRJarTask(compileR, rRoot.resolve("gen"), rJar) }
        classpath.add(rJar); compileDeps.add(compileR)

        // buildFeatures { viewBinding }: a lib generates bindings from its OWN layouts (against its own R) and,
        // unlike R, they are real code — compiled into the lib's output, so they dex into the AAR/jar.
        val vbDir = rRoot.resolve("gen-view-binding")
        val libViewBinding = if (facet.buildFeatures.viewBinding) TaskName(":${m.name}:generateViewBinding") else null
        if (libViewBinding != null) {
            tasks.task(libViewBinding) {
                GenerateViewBindingTask(libViewBinding, srcRoots(ContentRole.ANDROID_RES), facet.namespace, vbDir)
            }
            compileDeps.add(libViewBinding)
        }
        val libVbGenDirs = listOfNotNull(libViewBinding).map { vbDir }

        // compileKotlin (when the lib has `.kt`): runs against android.jar + the lib's own non-final R, ahead
        // of compileJava, which then sees its output. The Kotlin output IS dexed (it's the lib's code) — only
        // the R classes are kept out. Emits to the `kotlin-classes` sibling so dependers' classpaths find it.
        val libKotlin = classesOut.resolveSibling("kotlin-classes")
        val libHasKotlin = kotlin != null && containsKotlin(sourceRoots)
        if (libHasKotlin) {
            val compileKotlin = TaskName(":${m.name}:compileKotlin")
            val depKotlin = directModuleDeps(m, byId).filter { moduleHasKotlin(it) }.map { TaskName(":${it.name}:compileKotlin") }
            val kotlinCp = compileBootclasspath + libs.compileJars + moduleOutputs + upstreamKotlin + listOf(rJar)
            tasks.task(compileKotlin, listOf(compileR) + compileDeps.filter { it != compileR } + depKotlin) {
                AndroidKotlinCompileTask(m, compileKotlin, sourceRoots, rRoot.resolve("gen"), kotlinCp, libKotlin, level, bootClasspath, kotlin, plugins, extraGenDirs = libVbGenDirs)
            }
            classpath.add(libKotlin); compileDeps.add(compileKotlin)
        }

        val compile = TaskName(":${m.name}:compileJava")
        tasks.task(compile, compileDeps) {
            AndroidCompileTask(
                compile,
                sourceRoots,
                classesOut.resolveSibling("nogen"),
                classpath,
                classesOut,
                level,
                bootClasspath,
                extraGenDirs = libVbGenDirs,
            )
        }
        val procRes = TaskName(":${m.name}:processResources")
        tasks.task(procRes) { ProcessResourcesTask(procRes, srcRoots(ContentRole.RESOURCE), buildDir.resolve("resources")) }
        val classes = TaskName(":${m.name}:classes")
        val classDirs = listOf(classesOut) + if (libHasKotlin) listOf(libKotlin) else emptyList()
        tasks.task(classes, listOf(compile, procRes)) { LifecycleTask(classes, trackedDirs = classDirs + buildDir.resolve("resources")) }
        if (withJar) {
            val jar = TaskName(":${m.name}:jar")
            tasks.task(jar, listOf(classes)) { JarTask(jar, classDirs, jarPath(m)) }
        }
    }

    /**
     * The `assembleAar` terminal for an android-lib TARGET: package the library's already-registered build
     * outputs (`:lib:jar` classes + `:lib:generateR` R.txt/res) plus its manifest, assets, jni, and consumer
     * proguard rules into a `.aar` under `build/outputs/aar/`. AGP's `bundle<Variant>Aar` → `assemble<Variant>`.
     */
    private fun appendAar(tasks: TaskContainer, lib: Module, variant: AndroidVariant, facet: AndroidFacet) {
        val classesOut = Paths.get(lib.outputDir.path)
        val buildDir = classesOut.parent
        val moduleDir = buildDir.parent
        val libVariant = AndroidVariants.matchLibraryVariant(lib, variant, facet)
        fun srcRoots(role: ContentRole): List<Path> = libVariant?.let { roots(it, role) } ?: moduleRoots(lib, role)
        val rRoot = buildDir.resolve("intermediates").resolve("r")

        // Consumer keep rules the AAR ships (applied by a consuming app's R8): the build type's
        // consumerProguardFiles (module-relative) + inline proguardRules.
        val buildType = facet.buildType(variant.buildTypeName)
        val consumerProguard = (buildType?.consumerProguardFiles ?: emptyList()).map { moduleDir.resolve(it) }
        val inlineProguard = buildType?.proguardRules ?: emptyList()

        val bundleAar = TaskName(":${lib.name}:bundleAar")
        val deps = listOf(TaskName(":${lib.name}:jar"), TaskName(":${lib.name}:generateR"), TaskName(":${lib.name}:classes"))
        tasks.task(bundleAar, deps) {
            PackageAarTask(
                bundleAar,
                classesJar = jarPath(lib),
                manifest = moduleDir.resolve(facet.manifest),
                packageName = facet.namespace,
                resDirs = srcRoots(ContentRole.ANDROID_RES),
                rTxt = rRoot.resolve("R.txt"),
                assetsDirs = srcRoots(ContentRole.ASSETS),
                jniLibDirs = srcRoots(ContentRole.JNI_LIBS),
                consumerProguardFiles = consumerProguard,
                inlineProguardRules = inlineProguard,
                compileSdk = facet.compileSdk,
                outAar = aarPath(lib, variant.name),
            )
        }
        val assembleAar = TaskName(":${lib.name}:assembleAar")
        tasks.task(assembleAar, listOf(bundleAar)) {
            LifecycleTask(assembleAar, trackedFiles = listOf(aarPath(lib, variant.name)))
        }
    }

    private fun directModuleDeps(m: Module, byId: Map<ModuleId, Module>): List<Module> =
        m.dependencies.filterIsInstance<ModuleDependency>().mapNotNull { byId[it.target] }

    /** The signing config for [module]'s [buildType] variant — the resolver's answer, else the default debug [signing]. */
    private fun signingFor(module: Module, buildType: String): SigningConfig =
        signingResolver?.invoke(module, buildType) ?: signing

    /** True if any of [roots] holds a `.kt` file (so a `compileKotlin` step is needed). */
    private fun containsKotlin(roots: List<Path>): Boolean = roots.filter { Files.isDirectory(it) }
        .any { root -> Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".kt") } } }

    /** True if module [m] carries Kotlin sources (and Kotlin compilation is wired). */
    private fun moduleHasKotlin(m: Module): Boolean = kotlin != null && containsKotlin(moduleRoots(m, ContentRole.SOURCE))

    /** A module's content roots tagged with [role], across its non-test source sets. */
    private fun moduleRoots(m: Module, role: ContentRole): List<Path> =
        m.sourceSets.filter { it.scope != DependencyScope.TEST_IMPLEMENTATION }
            .flatMap { it.contentRoots }
            .filter { role in it.roles }
            .map { Paths.get(it.dir.path) }

    /** Every module transitively reached from [app] via module dependencies (excludes [app] itself). */
    private fun moduleClosure(app: Module, byId: Map<ModuleId, Module>): List<Module> {
        val out = LinkedHashMap<ModuleId, Module>()
        fun visit(m: Module) {
            for (d in directModuleDeps(m, byId)) if (out.put(d.id, d) == null) visit(d)
        }
        visit(app)
        return out.values.toList()
    }

    private fun roots(variant: AndroidVariant, role: ContentRole): List<Path> =
        variant.activeSourceSets.flatMap { it.contentRoots }
            .filter { role in it.roles }
            .map { Paths.get(it.dir.path) }

    /** Per-(module, variant) build paths under `<module>/build/`. */
    private inner class Layout(module: Module, variantName: String) {
        private val classesOut: Path = Paths.get(module.outputDir.path)   // <module>/build/classes
        private val buildDir: Path = classesOut.parent                  // <module>/build
        private val moduleDirField: Path = buildDir.parent              // <module>
        private val inter: Path = buildDir.resolve("intermediates").resolve("android").resolve(variantName)

        val mergedRes: Path = inter.resolve("merged-res")
        val compiledRes: Path = inter.resolve("res")
        val explodedAar: Path = inter.resolve("exploded-aar")
        val aarMetadataCheck: Path = inter.resolve("aar-metadata-check").resolve("check.stamp") // checkAarMetadata marker
        val mergedManifest: Path = inter.resolve("merged-manifest").resolve("AndroidManifest.xml")
        // The merged manifest + the debug-only log-bridge <provider> — what aapt2 links on an instrumented build.
        val instrumentedManifest: Path = inter.resolve("instrumented-manifest").resolve("AndroidManifest.xml")
        val generatedGmsRes: Path = buildDir.resolve("generated").resolve("res").resolve("google-services").resolve(variantName)
        val genJava: Path = inter.resolve("gen")
        // AGP's compile_and_runtime_not_namespaced_r_class_jar: the R classes as bytecode, not compiled R.java.
        val rJar: Path = inter.resolve("compile_and_runtime_not_namespaced_r_class_jar").resolve("R.jar")
        val viewBindingGen: Path = inter.resolve("gen-view-binding")  // ViewBinding <Layout>Binding.java
        val classes: Path = inter.resolve("classes")
        val kotlinClasses: Path = inter.resolve("kotlin-classes")   // K2 output (dexed as project scope)
        val dexArchives: Path = inter.resolve("dex-archives")   // dexBuilder scope roots + the project staging jar
        val projectArchives: Path = dexArchives.resolve("project")  // dexBuilder: app classes, per content hash
        val subArchives: Path = dexArchives.resolve("sub")          // dexBuilder: sub-module jars, per content hash
        val extArchives: Path = dexArchives.resolve("ext")          // dexBuilder: external libs, per content hash
        // The app's R.jar is dexed into its OWN archive root (not the external scope) and MERGED into the project
        // dex layer (mergeProjectDex) — AGP keeps R in the project scope. This keeps `extArchives` pure external
        // libraries, so mergeExtDex is stable across resource edits (only the project layer re-merges when R shifts).
        val rArchives: Path = dexArchives.resolve("r")              // dexBuilder: the app's R.jar, per content hash
        val projectDex: Path = inter.resolve("project-dex")     // mergeProjectDex output (app code)
        val libDex: Path = inter.resolve("lib-dex")             // mergeLibDex output (sub-module code)
        val extDex: Path = inter.resolve("ext-dex")             // mergeExtDex / dexExtLibs output (external library code)
        val rDex: Path = inter.resolve("r-dex")                 // dexRDex output (the app's R.jar as its own dex layer)
        val resourcesAp: Path = inter.resolve("resources.ap_")
        val aaptProguardRules: Path = inter.resolve("aapt_rules.txt") // keep rules aapt2 derives from the manifest
        val proguardDefaults: Path = inter.resolve("proguard-defaults") // bundled default proguard files, extracted
        val dex: Path = inter.resolve("dex")                    // mergeDex / R8 output (mono-/legacy-multidex)
        val desugarLibDex: Path = inter.resolve("desugar-lib-dex") // L8 output: the core-library desugaring runtime
        val desugarConfigJson: Path = inter.resolve("desugar.json")  // extracted from the desugar config jar
        val desugarKeepRules: Path = inter.resolve("l8-keep.pro")    // keep rules R8 emits for the L8 runtime shrink
        val protoAp: Path = inter.resolve("resources-proto.ap_")  // aapt2 proto link (resource-shrinking input)
        val shrunkProtoAp: Path = inter.resolve("resources-proto-shrunk.ap_") // R8-shrunk proto resources
        // mergeNativeLibs output (`<abi>/*.so`, packaged under lib/); mergeJavaResource output (root-level entries).
        val mergedNativeLibs: Path = inter.resolve("merged_native_libs")
        val mergedJavaRes: Path = inter.resolve("merged_java_res").resolve("merged-java-res.jar")
        val unsignedApk: Path = inter.resolve("${module.name}-$variantName-unsigned.apk")
        val signedApk: Path = buildDir.resolve("outputs").resolve("apk").resolve(variantName)
            .resolve("${module.name}-$variantName.apk")
        val baseModuleZip: Path = inter.resolve("bundle").resolve("base.zip")  // bundletool module input
        val unsignedAab: Path = inter.resolve("${module.name}-$variantName-unsigned.aab")
        // AGP's outputs/bundle/<variant>/<module>-<variant>.aab — the signed, uploadable app bundle.
        val signedAab: Path = buildDir.resolve("outputs").resolve("bundle").resolve(variantName)
            .resolve("${module.name}-$variantName.aab")
        // mapping.txt for stack-trace de-obfuscation; AGP's outputs/mapping/<variant>/mapping.txt.
        val mappingTxt: Path = buildDir.resolve("outputs").resolve("mapping").resolve(variantName).resolve("mapping.txt")

        val moduleDir: Path get() = this@Layout.moduleDirField
        fun manifest(facet: AndroidFacet): Path = moduleDirField.resolve(facet.manifest)
    }

    companion object {
        private fun String.cap() = replaceFirstChar { it.uppercase() }

        /** The signed-APK output path for [module] + [variantName] (matches [Layout.signedApk]) — so a host
         *  can locate the artifact to install after an `assemble`. */
        fun signedApkPath(module: Module, variantName: String): Path {
            val buildDir = Paths.get(module.outputDir.path).parent
            return buildDir.resolve("outputs").resolve("apk").resolve(variantName).resolve("${module.name}-$variantName.apk")
        }

        /** The signed-AAB output path for [module] + [variantName] (matches [Layout.signedAab]). */
        fun signedAabPath(module: Module, variantName: String): Path {
            val buildDir = Paths.get(module.outputDir.path).parent
            return buildDir.resolve("outputs").resolve("bundle").resolve(variantName).resolve("${module.name}-$variantName.aab")
        }

        /** The packaged `.aar` output path for an android-lib [module] + [variantName] (`assembleAar`).
         *  Mirrors AGP's `build/outputs/aar/<module>-<variant>.aar` so a host can locate the artifact. */
        fun aarPath(module: Module, variantName: String): Path {
            val buildDir = Paths.get(module.outputDir.path).parent
            return buildDir.resolve("outputs").resolve("aar").resolve("${module.name}-$variantName.aar")
        }

        private fun interDir(module: Module, variantName: String): Path =
            Paths.get(module.outputDir.path).parent.resolve("intermediates").resolve("android").resolve(variantName)

        /** aapt2-linked resources (`resources.ap_`: binary manifest + `resources.arsc` + compiled res XML) for
         *  [module]+[variantName] (matches [Layout.resourcesAp]) — the real `Resources` input for the on-device
         *  layout preview. Exists only after a build/assemble has linked resources. */
        fun resourcesApPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("resources.ap_")

        /** The non-namespaced `R.jar` (AGP's `compile_and_runtime_not_namespaced_r_class_jar`) for [module]+
         *  [variantName] (matches [Layout.rJar]) — the R classes (app + dep packages) whose ids match
         *  [resourcesApPath]'s arsc, so library views' `R.styleable.*` resolve correctly at inflate time. */
        fun rJarPath(module: Module, variantName: String): Path =
            interDir(module, variantName).resolve("compile_and_runtime_not_namespaced_r_class_jar").resolve("R.jar")

        /** The merged PROJECT dex dir (`mergeProjectDex` output, matches [Layout.projectDex]) — the app module's
         *  own compiled code (Java + Kotlin), already dexed. The on-device real-view preview adds these
         *  `classes*.dex` to its `DexClassLoader` so a project-source custom view resolves at inflate time.
         *  Produced by any build/assemble AND by the `prepareDex` ([BuildGoal.DEX]) goal. */
        fun projectDexPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("project-dex")

        /** The merged sub-module dex dir (`mergeLibDex` output, matches [Layout.libDex]) — dependency-MODULE code,
         *  already dexed. Exists only for a multi-module project. The real-view preview adds these too so a custom
         *  view declared in a dependency module also resolves. */
        fun libDexPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("lib-dex")

        /** The `javac`/`ecj` output dir (matches [Layout.classes]) — the module's compiled Java `.class` files.
         *  The real-view preview's INTERPRET path reads these directly (VM `ClassBytesSource` over the dir) so a
         *  project-source custom view runs interpreted, with no dexing and nothing loaded into ART. */
        fun classesPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("classes")

        /** The K2 output dir (matches [Layout.kotlinClasses]) — the module's compiled Kotlin `.class` files.
         *  Consumed alongside [classesPath] by the real-view preview's interpret path. */
        fun kotlinClassesPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("kotlin-classes")

        /** The aapt2-compiled resource archives dir for [module]+[variantName] (matches [Layout.compiledRes]) —
         *  the per-directory `res-*.zip` flats the link consumes. Reused as the base for the real-view preview's
         *  live relink (overlay the edited layout, skip recompiling the whole project). */
        fun compiledResPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("res")

        /** The merged (app + library) `AndroidManifest.xml` for [module]+[variantName] (matches
         *  [Layout.mergedManifest]) — the manifest the link uses; reused by the real-view preview relink. */
        fun mergedManifestPath(module: Module, variantName: String): Path =
            interDir(module, variantName).resolve("merged-manifest").resolve("AndroidManifest.xml")

        /** Where AARs are exploded for [module]+[variantName] (matches [Layout.explodedAar]) — passed to
         *  [AndroidLibraries.resolve] so the real-view preview resolves the SAME runtime library set the build
         *  dexes (reusing the build's explosion when present). */
        fun explodedAarPath(module: Module, variantName: String): Path = interDir(module, variantName).resolve("exploded-aar")

        /**
         * AGP's external-library merge threshold (`DexMergingTask.LIBRARIES_MERGING_THRESHOLD` / its M+
         * cousin): at/below it, external libs are merged per-library (more `classes*.dex` files but finer
         * change isolation); above it they collapse into one group to keep the dex-file count in check.
         */
        private fun extMergeThreshold(minSdk: Int): Int = if (minSdk < 23) 50 else 500

        private fun levelOf(level: LanguageLevel): String = when (level) {
            LanguageLevel.JAVA_8 -> "8"
            LanguageLevel.JAVA_11 -> "11"
            LanguageLevel.JAVA_17 -> "17"
            LanguageLevel.JAVA_21 -> "21"
        }

        /**
         * Desktop wiring: every tool is a subprocess over an installed SDK (`java -cp d8.jar …`,
         * `java -jar apksigner.jar …`, native aapt2/zipalign). No statically-linked tool jars needed.
         */
        fun subprocess(sdk: AndroidSdk, signing: SigningConfig, bootClasspath: List<Path> = emptyList(), kotlin: IncrementalKotlinCompiler? = null, plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS, dexCacheRoot: Path? = null, desugarLib: DesugarLib? = null, signingResolver: ((Module, String) -> SigningConfig?)? = null, appLogRuntime: () -> AndroidAppLogRuntime? = { null }): AndroidBuildSystem =
            AndroidBuildSystem(sdk, signing, bootClasspath, kotlin = kotlin, plugins = plugins, dexCacheRoot = dexCacheRoot, desugarLib = desugarLib, signingResolver = signingResolver, appLogRuntime = appLogRuntime)

        /**
         * On-device-shaped wiring: the native tools (aapt2, zipalign) run as subprocesses against the
         * platform binaries, while the pure-Java tools (D8, apksigner) run in-process via their
         * statically-linked APIs ([D8InProcessDexer]/[ApksigSigner]). This is what `:ide-android` uses on
         * ART (where `java -jar` is impossible); the desktop test runs it too, so the on-device dex/sign
         * code path is exercised on the host.
         */
        fun inProcess(sdk: AndroidSdk, signing: SigningConfig, bootClasspath: List<Path> = emptyList(), kotlin: IncrementalKotlinCompiler? = null, plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS, dexCacheRoot: Path? = null, desugarLib: DesugarLib? = null, signingResolver: ((Module, String) -> SigningConfig?)? = null, shrinker: Shrinker? = null, dexer: Dexer? = null, mergeDexer: Dexer? = null, mergeChunk: () -> Int = { DexMergeTask.DEFAULT_MERGE_CHUNK }, appLogRuntime: () -> AndroidAppLogRuntime? = { null }): AndroidBuildSystem =
            AndroidBuildSystem(
                sdk, signing, bootClasspath,
                // The dexBuilder ARCHIVE dexer. The host can inject a forked-VM D8 (an [OffHeapArchiveDexer]) so a
                // big project jar / cold library archives off the app heap and several libraries archive at once;
                // default keeps archiving in-process (app-heap-bounded by DexConcurrency).
                dexer = dexer ?: D8InProcessDexer(),
                // R8 is the heaviest in-process step (whole-program). On ART the host can inject a forked-VM
                // shrinker that runs R8 with a bigger -Xmx than the app heap cap; default keeps it in-process.
                shrinker = shrinker ?: R8InProcessShrinker(),
                // The dex MERGE is the debug-path memory peak; the host can inject a forked-VM D8 for it.
                mergeDexer = mergeDexer ?: D8InProcessDexer(),
                signer = ApksigSigner(),
                bundleSigner = ApksigBundleSigner(),   // ART: v1-sign the .aab in-process (no jarsigner)
                kotlin = kotlin,
                plugins = plugins,
                dexCacheRoot = dexCacheRoot,
                desugarLib = desugarLib,
                signingResolver = signingResolver,
                mergeChunk = mergeChunk,
                appLogRuntime = appLogRuntime,
            )

        /** A debug-signed [subprocess] build system, creating the shared debug keystore on demand. */
        fun debug(sdk: AndroidSdk, debugKeystore: Path, bootClasspath: List<Path> = emptyList()): AndroidBuildSystem =
            subprocess(sdk, DebugKeystore.getOrCreate(debugKeystore, sdk.keytool), bootClasspath)
    }
}
