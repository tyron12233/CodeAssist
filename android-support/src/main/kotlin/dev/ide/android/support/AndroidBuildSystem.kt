package dev.ide.android.support

import dev.ide.android.support.tasks.Aapt2CompileTask
import dev.ide.android.support.tasks.Aapt2LinkTask
import dev.ide.android.support.tasks.AndroidCompileTask
import dev.ide.android.support.tasks.AndroidKotlinCompileTask
import dev.ide.android.support.tasks.DexArchiveBuilderTask
import dev.ide.android.support.tasks.DexMergeTask
import dev.ide.android.support.tasks.GenerateLibraryRTask
import dev.ide.android.support.tasks.MergeResourcesTask
import dev.ide.android.support.tasks.PackageApkTask
import dev.ide.android.support.tasks.R8MinifyTask
import dev.ide.android.support.tasks.SignApkTask
import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.Aapt2Subprocess
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.ApkSigner
import dev.ide.android.support.tools.ApkSignerTool
import dev.ide.android.support.tools.ApksigSigner
import dev.ide.android.support.tools.D8Dexer
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.R8InProcessShrinker
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
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaPlugin
import dev.ide.build.engine.KotlinCompile
import dev.ide.build.engine.LifecycleTask
import dev.ide.build.engine.ProcessResourcesTask
import dev.ide.build.engine.jarPath
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
 * build-engine's generic [dev.ide.build.TaskExecutor]. Compilation is the injected [JavaCompile] port
 * (JDT in production); the Android tools are injected ports defaulting to the SDK subprocess wirings.
 *
 * It shares [BuildSystemId.NATIVE] with the Java build system and distinguishes itself by [supports];
 * a host composes the two into one native build system that dispatches by module type.
 */
class AndroidBuildSystem(
    private val javaCompile: JavaCompile,
    private val sdk: AndroidSdk,
    private val signing: SigningConfig,
    private val aapt2: Aapt2 = Aapt2Subprocess(sdk.aapt2),
    private val dexer: Dexer = D8Dexer(sdk.d8Jar, sdk.javaLauncher),
    private val shrinker: Shrinker = R8Subprocess(sdk.d8Jar, sdk.javaLauncher),
    private val signer: ApkSigner = ApkSignerTool(sdk.apksignerJar, sdk.zipalign, sdk.javaLauncher),
    private val kotlinCompile: KotlinCompile? = null,
    /** Global content-addressed library-dex cache (e.g. the host's shared caches dir); null = per-project only. */
    private val dexCacheRoot: Path? = null,
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
            AndroidVariants.compute(m).map {
                TaskDescriptor("assemble${it.name.cap()}", "build", "Assemble the ${it.name} APK of :${m.name}")
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
        val javaPlugin = JavaPlugin(javaCompile, kotlinCompile)
        val withJar = request.goal != BuildGoal.COMPILE_ONLY
        val registered = HashSet<ModuleId>()
        for (app in targets) {
            val variant = AndroidVariants.select(app, request.variant.name)
                ?: AndroidVariants.defaultVariant(app) ?: continue
            for (m in moduleClosure(app, byId)) {
                if (!registered.add(m.id)) continue
                if (m.facets.get(AndroidFacet.KEY) != null) registerAndroidLibrary(tasks, m, byId, withJar)
                else javaPlugin.registerModule(tasks, m, byId, withJar)   // reuse the Java plugin
            }
            appendApp(tasks, app, variant, request.goal, byId)
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

        val libs = AndroidLibraries.resolve(app, layout.explodedAar)
        val appModuleOutputs = app.classpath(DependencyScope.IMPLEMENTATION).entries
            .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }

        val closure = moduleClosure(app, byId)
        val depAndroidLibs = closure.filter { it.facets.get(AndroidFacet.KEY) != null }
        // R is generated for every dependency-lib package AND every external AAR package (so an AAR's own
        // classes + custom-view attrs resolve against the app-linked resource table).
        val extraPackages = (depAndroidLibs.mapNotNull { it.facets.get(AndroidFacet.KEY)?.namespace } +
            libs.aarPackages).distinct()

        val mergeResInputs = depAndroidLibs.flatMap { moduleRoots(it, ContentRole.ANDROID_RES) } +
            libs.resDirs + roots(variant, ContentRole.ANDROID_RES)
        val sourceRoots = roots(variant, ContentRole.SOURCE)
        val assetsDirs = depAndroidLibs.flatMap { moduleRoots(it, ContentRole.ASSETS) } +
            libs.assetsDirs + roots(variant, ContentRole.ASSETS)
        val level = levelOf(app.languageLevel)
        val release = facet.buildType(variant.buildTypeName)?.debuggable == false
        val directDepCompiles = directModuleDeps(app, byId).map { TaskName(":${it.name}:compileJava") }

        // Kotlin: upstream modules' `kotlin-classes` (sibling of their Java output) join the compile classpath;
        // when the app itself has `.kt`, its own `compileKotlin` runs first and its output is added too.
        val upstreamKotlin = appModuleOutputs.map { it.resolveSibling("kotlin-classes") }
        val appHasKotlin = kotlinCompile != null && containsKotlin(sourceRoots)
        val directDepKotlin = directModuleDeps(app, byId).filter { moduleHasKotlin(it) }
            .map { TaskName(":${it.name}:compileKotlin") }
        val kotlinClasspath = compileBootclasspath + libs.compileJars + appModuleOutputs + upstreamKotlin
        val compileClasspath = kotlinClasspath + if (appHasKotlin) listOf(layout.kotlinClasses) else emptyList()

        val mergeRes = step("mergeResources")
        val aapt2Compile = step("aapt2Compile")
        val aapt2Link = step("aapt2Link")
        val compileKotlin = step("compileKotlin")
        val compile = step("compileJava")
        tasks.task(mergeRes) { MergeResourcesTask(mergeRes, mergeResInputs, layout.mergedRes) }
        tasks.task(aapt2Compile, listOf(mergeRes)) { Aapt2CompileTask(aapt2Compile, listOf(layout.mergedRes), layout.compiledRes, aapt2) }
        tasks.task(aapt2Link, listOf(aapt2Compile)) {
            Aapt2LinkTask(aapt2Link, layout.compiledRes, layout.manifest(facet), sdk.androidJar,
                facet.namespace, extraPackages, facet.minSdk, facet.targetSdk, layout.genJava, layout.resourcesAp, aapt2)
        }
        if (appHasKotlin) {
            tasks.task(compileKotlin, listOf(aapt2Link) + directDepCompiles + directDepKotlin) {
                AndroidKotlinCompileTask(compileKotlin, sourceRoots, layout.genJava, kotlinClasspath, layout.kotlinClasses, level, kotlinCompile!!)
            }
        }
        // The app compiles the WHOLE gen tree — its own R plus the final R for every dependency-lib package.
        tasks.task(compile, listOf(aapt2Link) + directDepCompiles + (if (appHasKotlin) listOf(compileKotlin) else emptyList())) {
            AndroidCompileTask(compile, sourceRoots, layout.genJava, compileClasspath, layout.classes, level, javaCompile)
        }
        // The app's project-scope dex covers both the Java and (when present) the Kotlin output.
        val appProjectClasses = listOf(layout.classes) + if (appHasKotlin) listOf(layout.kotlinClasses) else emptyList()

        if (goal == BuildGoal.COMPILE_ONLY) return

        val pkg = step("packageApk")
        val sign = step("sign")

        // Inputs to dex, by AGP scope: sub-module `jar` artifacts (consumed BY NAME) and external libraries.
        val subProjectJars = closure.map { jarPath(it) }
        val moduleJarProducers = closure.map { TaskName(":${it.name}:jar") }
        val externalJars = libs.dexJars

        // The merged dex layers the packager assembles (renumbered into one classes*.dex set) + packageApk's deps.
        val dexDirs: List<Path>
        val pkgDeps: List<TaskName>

        if (facet.buildType(variant.buildTypeName)?.minifyEnabled == true) {
            // Release: R8 shrinks + dexes app classes + every library jar in one pass into a single dex dir.
            val minify = TaskName(":${app.name}:minify${v}WithR8")
            tasks.task(minify, listOf(aapt2Link, compile) + moduleJarProducers) {
                R8MinifyTask(minify, appProjectClasses + subProjectJars + externalJars, sdk.androidJar, facet.minSdk,
                    layout.aaptProguardRules, layout.dexArchives.resolve("r8-staging"), layout.dex, shrinker)
            }
            dexDirs = listOf(layout.dex)
            pkgDeps = listOf(aapt2Link, minify)
        } else {
            // Debug: ONE dexBuilder archives each scope (per-class, content-addressed, internally incremental);
            // the scope merges combine the archives into indexed dex. AGP names: dexBuilder → mergeProjectDex /
            // mergeLibDex / mergeExtDex (native multidex), or → mergeDex (MERGE_ALL) for mono/legacy multidex.
            val dexBuilder = step("dexBuilder")
            tasks.task(dexBuilder, listOf(compile) + moduleJarProducers) {
                DexArchiveBuilderTask(dexBuilder, appProjectClasses, subProjectJars, externalJars, sdk.androidJar,
                    facet.minSdk, release, layout.dexArchives.resolve("project.jar"),
                    layout.projectArchives, layout.subArchives, layout.extArchives, dexer, dexCacheRoot)
            }
            if (facet.minSdk >= 21) {
                // Native multidex: ART loads many dex, so keep the scopes split for the best incrementality.
                val mergeProjectDex = step("mergeProjectDex")
                tasks.task(mergeProjectDex, listOf(dexBuilder)) {
                    DexMergeTask(mergeProjectDex, listOf(layout.projectArchives), sdk.androidJar, facet.minSdk, release, layout.projectDex, dexer)
                }
                val dirs = arrayListOf(layout.projectDex)
                val deps = arrayListOf(aapt2Link, mergeProjectDex)
                if (subProjectJars.isNotEmpty()) {
                    val mergeLibDex = step("mergeLibDex")
                    tasks.task(mergeLibDex, listOf(dexBuilder)) {
                        DexMergeTask(mergeLibDex, listOf(layout.subArchives), sdk.androidJar, facet.minSdk, release, layout.libDex, dexer)
                    }
                    dirs.add(layout.libDex); deps.add(mergeLibDex)
                }
                if (externalJars.isNotEmpty()) {
                    val mergeExtDex = step("mergeExtDex")
                    // Below AGP's LIBRARIES_MERGING_THRESHOLD: merge per-library (more dex files, finer isolation).
                    val perLib = externalJars.size <= extMergeThreshold(facet.minSdk)
                    tasks.task(mergeExtDex, listOf(dexBuilder)) {
                        DexMergeTask(mergeExtDex, listOf(layout.extArchives), sdk.androidJar, facet.minSdk, release, layout.extDex, dexer, groupPerBucket = perLib)
                    }
                    dirs.add(layout.extDex); deps.add(mergeExtDex)
                }
                dexDirs = dirs; pkgDeps = deps
            } else {
                // Mono-/legacy-multidex (minSdk < 21): merge every scope into a single classes.dex set.
                val mergeDex = step("mergeDex")
                tasks.task(mergeDex, listOf(dexBuilder)) {
                    DexMergeTask(mergeDex, listOf(layout.projectArchives, layout.subArchives, layout.extArchives),
                        sdk.androidJar, facet.minSdk, release, layout.dex, dexer)
                }
                dexDirs = listOf(layout.dex); pkgDeps = listOf(aapt2Link, mergeDex)
            }
        }

        tasks.task(pkg, pkgDeps) { PackageApkTask(pkg, layout.resourcesAp, dexDirs, assetsDirs, libs.jniLibDirs, layout.unsignedApk) }
        tasks.task(sign, listOf(pkg)) { SignApkTask(sign, layout.unsignedApk, layout.signedApk, signing, signer) }
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
    private fun registerAndroidLibrary(tasks: TaskContainer, m: Module, byId: Map<ModuleId, Module>, withJar: Boolean) {
        val classesOut = Paths.get(m.outputDir.path)
        val buildDir = classesOut.parent
        val libs = AndroidLibraries.resolve(m, buildDir.resolve("intermediates").resolve("exploded-aar"))
        val moduleOutputs = m.classpath(DependencyScope.IMPLEMENTATION).entries
            .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }
        val level = levelOf(m.languageLevel)
        val facet = m.facets.get(AndroidFacet.KEY)!!
        // Upstream modules' Kotlin output (sibling of their Java output) joins this lib's compile classpath.
        val upstreamKotlin = moduleOutputs.map { it.resolveSibling("kotlin-classes") }
        val classpath = ArrayList(compileBootclasspath + libs.compileJars + moduleOutputs + upstreamKotlin)
        val compileDeps = directModuleDeps(m, byId).map { TaskName(":${it.name}:compileJava") }.toMutableList()
        val sourceRoots = moduleRoots(m, ContentRole.SOURCE)

        val rRoot = buildDir.resolve("intermediates").resolve("r")
        val generateR = TaskName(":${m.name}:generateR")
        val compileR = TaskName(":${m.name}:compileR")
        tasks.task(generateR) {
            GenerateLibraryRTask(generateR, moduleRoots(m, ContentRole.ANDROID_RES), buildDir.parent.resolve(facet.manifest),
                sdk.androidJar, facet.namespace, facet.minSdk,
                rRoot.resolve("res"), rRoot.resolve("gen"), rRoot.resolve("lib.ap_"), rRoot.resolve("AndroidManifest.xml"), aapt2)
        }
        val rClasses = rRoot.resolve("classes")
        tasks.task(compileR, listOf(generateR)) { AndroidCompileTask(compileR, emptyList(), rRoot.resolve("gen"), compileBootclasspath, rClasses, level, javaCompile) }
        classpath.add(rClasses); compileDeps.add(compileR)

        // compileKotlin (when the lib has `.kt`): runs against android.jar + the lib's own non-final R, ahead
        // of compileJava, which then sees its output. The Kotlin output IS dexed (it's the lib's code) — only
        // the R classes are kept out. Emits to the `kotlin-classes` sibling so dependers' classpaths find it.
        val libKotlin = classesOut.resolveSibling("kotlin-classes")
        val libHasKotlin = kotlinCompile != null && containsKotlin(sourceRoots)
        if (libHasKotlin) {
            val compileKotlin = TaskName(":${m.name}:compileKotlin")
            val depKotlin = directModuleDeps(m, byId).filter { moduleHasKotlin(it) }.map { TaskName(":${it.name}:compileKotlin") }
            val kotlinCp = compileBootclasspath + libs.compileJars + moduleOutputs + upstreamKotlin + rClasses
            tasks.task(compileKotlin, listOf(compileR) + compileDeps.filter { it != compileR } + depKotlin) {
                AndroidKotlinCompileTask(compileKotlin, sourceRoots, rRoot.resolve("gen"), kotlinCp, libKotlin, level, kotlinCompile!!)
            }
            classpath.add(libKotlin); compileDeps.add(compileKotlin)
        }

        val compile = TaskName(":${m.name}:compileJava")
        tasks.task(compile, compileDeps) {
            AndroidCompileTask(compile, sourceRoots, classesOut.resolveSibling("nogen"), classpath, classesOut, level, javaCompile)
        }
        val procRes = TaskName(":${m.name}:processResources")
        tasks.task(procRes) { ProcessResourcesTask(procRes, moduleRoots(m, ContentRole.RESOURCE), buildDir.resolve("resources")) }
        val classes = TaskName(":${m.name}:classes")
        val classDirs = listOf(classesOut) + if (libHasKotlin) listOf(libKotlin) else emptyList()
        tasks.task(classes, listOf(compile, procRes)) { LifecycleTask(classes, trackedDirs = classDirs + buildDir.resolve("resources")) }
        if (withJar) {
            val jar = TaskName(":${m.name}:jar")
            tasks.task(jar, listOf(classes)) { JarTask(jar, classDirs, jarPath(m)) }
        }
    }

    private fun directModuleDeps(m: Module, byId: Map<ModuleId, Module>): List<Module> =
        m.dependencies.filterIsInstance<ModuleDependency>().mapNotNull { byId[it.target] }

    /** True if any of [roots] holds a `.kt` file (so a `compileKotlin` step is needed). */
    private fun containsKotlin(roots: List<Path>): Boolean = roots.filter { Files.isDirectory(it) }
        .any { root -> Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".kt") } } }

    /** True if module [m] carries Kotlin sources (and Kotlin compilation is wired). */
    private fun moduleHasKotlin(m: Module): Boolean = kotlinCompile != null && containsKotlin(moduleRoots(m, ContentRole.SOURCE))

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
        private val moduleDir: Path = buildDir.parent                   // <module>
        private val inter: Path = buildDir.resolve("intermediates").resolve("android").resolve(variantName)

        val mergedRes: Path = inter.resolve("merged-res")
        val compiledRes: Path = inter.resolve("res")
        val explodedAar: Path = inter.resolve("exploded-aar")
        val genJava: Path = inter.resolve("gen")
        val classes: Path = inter.resolve("classes")
        val kotlinClasses: Path = inter.resolve("kotlin-classes")   // K2 output (dexed as project scope)
        val dexArchives: Path = inter.resolve("dex-archives")   // dexBuilder scope roots + the project staging jar
        val projectArchives: Path = dexArchives.resolve("project")  // dexBuilder: app classes, per content hash
        val subArchives: Path = dexArchives.resolve("sub")          // dexBuilder: sub-module jars, per content hash
        val extArchives: Path = dexArchives.resolve("ext")          // dexBuilder: external libs, per content hash
        val projectDex: Path = inter.resolve("project-dex")     // mergeProjectDex output (app code)
        val libDex: Path = inter.resolve("lib-dex")             // mergeLibDex output (sub-module code)
        val extDex: Path = inter.resolve("ext-dex")             // mergeExtDex output (external library code)
        val resourcesAp: Path = inter.resolve("resources.ap_")
        val aaptProguardRules: Path = inter.resolve("aapt_rules.txt") // keep rules aapt2 derives from the manifest
        val dex: Path = inter.resolve("dex")                    // mergeDex / R8 output (mono-/legacy-multidex)
        val unsignedApk: Path = inter.resolve("${module.name}-$variantName-unsigned.apk")
        val signedApk: Path = buildDir.resolve("outputs").resolve("apk").resolve(variantName)
            .resolve("${module.name}-$variantName.apk")

        fun manifest(facet: AndroidFacet): Path = moduleDir.resolve(facet.manifest)
    }

    companion object {
        private fun String.cap() = replaceFirstChar { it.uppercase() }

        /** The signed-APK output path for [module] + [variantName] (matches [Layout.signedApk]) — so a host
         *  can locate the artifact to install after an `assemble`. */
        fun signedApkPath(module: Module, variantName: String): Path {
            val buildDir = Paths.get(module.outputDir.path).parent
            return buildDir.resolve("outputs").resolve("apk").resolve(variantName).resolve("${module.name}-$variantName.apk")
        }

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
        fun subprocess(javaCompile: JavaCompile, sdk: AndroidSdk, signing: SigningConfig, kotlinCompile: KotlinCompile? = null, dexCacheRoot: Path? = null): AndroidBuildSystem =
            AndroidBuildSystem(javaCompile, sdk, signing, kotlinCompile = kotlinCompile, dexCacheRoot = dexCacheRoot)

        /**
         * On-device-shaped wiring: the native tools (aapt2, zipalign) run as subprocesses against the
         * platform binaries, while the pure-Java tools (D8, apksigner) run in-process via their
         * statically-linked APIs ([D8InProcessDexer]/[ApksigSigner]). This is what `:ide-android` uses on
         * ART (where `java -jar` is impossible); the desktop test runs it too, so the on-device dex/sign
         * code path is exercised on the host.
         */
        fun inProcess(javaCompile: JavaCompile, sdk: AndroidSdk, signing: SigningConfig, kotlinCompile: KotlinCompile? = null, dexCacheRoot: Path? = null): AndroidBuildSystem =
            AndroidBuildSystem(
                javaCompile, sdk, signing,
                dexer = D8InProcessDexer(),
                shrinker = R8InProcessShrinker(),
                signer = ApksigSigner(sdk.zipalign),
                kotlinCompile = kotlinCompile,
                dexCacheRoot = dexCacheRoot,
            )

        /** A debug-signed [subprocess] build system, creating the shared debug keystore on demand. */
        fun debug(javaCompile: JavaCompile, sdk: AndroidSdk, debugKeystore: Path): AndroidBuildSystem =
            subprocess(javaCompile, sdk, DebugKeystore.getOrCreate(debugKeystore, sdk.keytool))
    }
}
