package dev.ide.core.gradle

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildType
import dev.ide.android.support.DefaultProguardFiles
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.ModuleDependency
import dev.ide.model.OrderEntry
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.impl.LibraryData
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ModuleData
import dev.ide.model.impl.ProjectData
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a native CodeAssist project out as a real Gradle project: the model (`workspace.json` +
 * `module.toml` + the facets) is rendered into `settings.gradle.kts`, a root build script, and one
 * `build.gradle.kts` per module, and the sources travel alongside them in a zip the user can open in
 * Android Studio or build with `gradle`.
 *
 * The model is not a Gradle build, so what crosses is what Gradle also models: modules, their type and
 * language level, source roots, declared dependencies, and the Android facet (SDK levels, build types,
 * flavors, build features, packaging). What Gradle has no equivalent for (a signing config that lives in
 * the app's keystore registry, a bundled KSP processor with no declared Gradle plugin, a library with no
 * Maven coordinate) is left out of the scripts and reported as a note instead of being guessed at. The
 * reverse direction is [dev.ide.core.GradleImport]; the two are symmetric enough that an export re-imports.
 */
object GradleProjectExport {

    /**
     * The plugin versions the generated scripts pin. AGP 8.x is paired with the classic DSL these scripts
     * are written in (`kotlin.android` applied separately, no built-in Kotlin), which is what current
     * Android Studio reads. Kotlin is the version the IDE compiles projects with, so sources that build
     * here build there. All three are declared in one place (the root script), so bumping them is one edit.
     */
    const val AGP_VERSION: String = "8.13.0"
    const val KOTLIN_VERSION: String = "2.4.0"
    const val GRADLE_VERSION: String = "8.13"

    /** The core-library desugaring runtime AGP pairs with `isCoreLibraryDesugaringEnabled`. */
    private const val DESUGAR_JDK_LIBS = "com.android.tools:desugar_jdk_libs:2.1.5"

    /** The library name the IDE provisions for its bundled Kotlin standard library (never a Maven coord). */
    private const val BUNDLED_STDLIB = "kotlin-stdlib"

    private const val NOTES_FILE = "GRADLE-EXPORT.md"

    /** Plugin ids, resolved once so the root script and the module scripts cannot drift apart. */
    private const val PLUGIN_APP = "com.android.application"
    private const val PLUGIN_LIB = "com.android.library"
    private const val PLUGIN_KOTLIN_ANDROID = "org.jetbrains.kotlin.android"
    private const val PLUGIN_KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
    private const val PLUGIN_COMPOSE = "org.jetbrains.kotlin.plugin.compose"
    private const val PLUGIN_SERIALIZATION = "org.jetbrains.kotlin.plugin.serialization"
    private const val PLUGIN_PARCELIZE = "org.jetbrains.kotlin.plugin.parcelize"
    private const val PLUGIN_JAVA_LIBRARY = "java-library"
    private const val PLUGIN_APPLICATION = "application"

    /** Plugins Gradle ships itself, so they carry no version in the root `plugins` block. */
    private val CORE_PLUGINS = setOf(PLUGIN_JAVA_LIBRARY, PLUGIN_APPLICATION)

    /** The generated build files (path relative to the project root, in write order) plus the notes. */
    data class Rendered(val files: Map<String, String>, val notes: List<String>)

    /** A finished export: the archive that was written and what could not be carried into it. */
    data class Outcome(val zip: Path, val notes: List<String>)

    /**
     * Render the Gradle build files for the project rooted at [projectDir] without writing anything.
     * Throws when the directory holds no readable project model.
     */
    fun render(projectDir: Path): Rendered = renderAt(projectDir).second

    /** [render], keeping the project root the files are relative to (the zip needs to walk it). */
    private fun renderAt(projectDir: Path): Pair<Path, Rendered> {
        val workspace = ModelPersistence.load(projectDir)
        val project = workspace.projects.firstOrNull() ?: error("No project in ${projectDir.fileName}")
        val root = projectRoot(projectDir, project)
        val libraries = (workspace.libraries + project.libraries).associateBy { it.name }
        val notes = ArrayList<String>()
        val modules = readModules(root, project, settingsProps(projectDir), notes)
        return root to Rendered(renderFiles(root, project, modules, libraries, notes), notes.distinct())
    }

    /**
     * Export the project at [projectDir] to the zip at [out]: every source file that isn't IDE state or a
     * build output, plus the generated Gradle scripts, all under a single [topFolder] entry so unzipping
     * lands one clean project directory.
     */
    fun exportZip(projectDir: Path, out: Path, topFolder: String): Outcome {
        val (root, rendered) = renderAt(projectDir)
        val prefix = topFolder.trim('/').ifEmpty { "project" }
        out.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(out)).use { zip ->
            for (file in sourceFiles(root)) {
                val rel = root.relativize(file).toString().replace(File.separatorChar, '/')
                // A generated script wins over a same-named file already in the tree, so the export is
                // never two build files deep.
                if (rel in rendered.files) continue
                zip.putNextEntry(ZipEntry("$prefix/$rel"))
                Files.copy(file, zip)
                zip.closeEntry()
            }
            for ((rel, text) in rendered.files) {
                zip.putNextEntry(ZipEntry("$prefix/$rel"))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return Outcome(out, rendered.notes)
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the model
    // ---------------------------------------------------------------------------------------------

    /** One module as the exporter needs it: the model data plus what only disk and the prefs can say. */
    private class Exported(
        val data: ModuleData,
        val dir: Path,
        /** The module directory relative to the project root; empty when the module IS the root. */
        val relDir: String,
        /** The Gradle path (`:app`, `:features:home`), or `:` for a module that IS the root project. */
        val path: String,
        val android: AndroidFacet?,
        val kotlin: Boolean,
        val mainClass: String?,
        /**
         * The Kotlin compiler plugins the module needs. The IDE turns each on from a classpath probe (add
         * the runtime, the plugin runs), so the facet flag is only half the answer: a project that declared
         * the Compose runtime without ever touching the Build Features toggle still compiles as Compose here
         * and has to compile as Compose there.
         */
        val compose: Boolean,
        val parcelize: Boolean,
        val serialization: Boolean,
    ) {
        val isRoot: Boolean get() = path == ":"
        val buildFile: String get() = if (isRoot) "build.gradle.kts" else "$relDir/build.gradle.kts"
    }

    private fun projectRoot(projectDir: Path, project: ProjectData): Path {
        val rel = project.rootRelPath.trim('/')
        return if (rel.isEmpty() || rel == ".") projectDir else projectDir.resolve(rel)
    }

    private fun settingsProps(projectDir: Path): Properties = Properties().apply {
        val file = projectDir.resolve(".platform/settings.properties")
        if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use { load(it) } }
    }

    private fun readModules(
        root: Path,
        project: ProjectData,
        settings: Properties,
        notes: MutableList<String>,
    ): List<Exported> = project.modules.map { data ->
        val relDir = data.dirRelPath.trim('/').takeIf { it != "." }.orEmpty()
        val dir = if (relDir.isEmpty()) root else root.resolve(relDir)
        val android = data.facets.firstOrNull { it.tomlTable == AndroidFacetCodec.tomlTable }
            ?.let { facet -> runCatching { AndroidFacetCodec.decode(facet.values) }.getOrNull() }
        if (android == null && data.typeId.startsWith("android")) {
            notes += "Module '${data.name}' is an Android module with no readable Android settings, " +
                "so it was written as a plain JVM module. Check its build file."
        }
        val kotlin = hasKotlinSources(data, dir)
        val declared = declaredCoordinates(data)
        val features = android?.buildFeatures
        val compose = kotlin && (features?.compose == true || declared.any(::isComposeArtifact))
        if (compose && features?.compose != true) {
            notes += "Module '${data.name}' declares Compose libraries, so the export applies the Compose " +
                "compiler plugin. CodeAssist turns it on from the classpath instead of a build flag."
        }
        Exported(
            data = data,
            dir = dir,
            relDir = relDir,
            path = gradlePath(relDir),
            android = android,
            kotlin = kotlin,
            mainClass = settings.getProperty("module.mainClass.${data.id}")?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (android == null) detectMainClass(data, dir, notes) else null,
            compose = compose,
            parcelize = kotlin && (features?.parcelize == true || declared.any { it.startsWith("org.jetbrains.kotlin:kotlin-parcelize") }),
            serialization = kotlin &&
                (features?.serialization == true || declared.any { it.startsWith("org.jetbrains.kotlinx:kotlinx-serialization") }),
        )
    }

    /** The `group:name` of everything the module declares, which is what the compiler-plugin probes read. */
    private fun declaredCoordinates(data: ModuleData): List<String> = data.dependencies.mapNotNull { entry ->
        val coordinate = when (entry) {
            is LibraryDependency -> entry.library.name
            is PlatformDependency -> entry.bom.toString()
            else -> return@mapNotNull null
        }
        coordinate.split(':').takeIf { it.size >= 2 }?.let { "${it[0]}:${it[1]}" }
    }

    /** The same shape the Compose plugin probe recognises: the Compose libraries and their `-compose` bridges. */
    private fun isComposeArtifact(groupName: String): Boolean {
        val group = groupName.substringBefore(':')
        val name = groupName.substringAfter(':')
        return group.startsWith("androidx.compose") || group.startsWith("org.jetbrains.compose") ||
            name.endsWith("-compose") || name == "compose-bom"
    }

    /** `features/home` becomes `:features:home`; a module sitting at the project root becomes `:`. */
    private fun gradlePath(relDir: String): String {
        if (relDir.isEmpty()) return ":"
        return relDir.split('/').filter { it.isNotEmpty() }.joinToString("") { ":" + sanitize(it) }
    }

    /** Gradle project names allow letters, digits, `-`, `_` and `.`; anything else becomes an underscore. */
    private fun sanitize(segment: String): String =
        segment.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")

    /**
     * The entry point of a JVM module, so the export can apply the `application` plugin and stay runnable.
     * The IDE knows this from its index, which an export has no reason to boot, so it is read off disk: a
     * top-level `fun main` or a `static void main` plus the file's package. A module with several is
     * reported rather than silently pinned to whichever was found first.
     */
    private fun detectMainClass(data: ModuleData, dir: Path, notes: MutableList<String>): String? {
        val found = LinkedHashSet<String>()
        for (root in sourceRootsOf(data, dir)) {
            Files.walk(root).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { n -> n.endsWith(".kt") || n.endsWith(".java") } }
                    .collect(Collectors.toList())
                for (file in files) {
                    val text = runCatching { Files.readAllBytes(file).toString(Charsets.UTF_8) }.getOrNull() ?: continue
                    val kotlin = file.fileName.toString().endsWith(".kt")
                    val declares = if (kotlin) TOP_LEVEL_MAIN_KT.containsMatchIn(text) else MAIN_JAVA.containsMatchIn(text)
                    if (!declares) continue
                    val pkg = PACKAGE_LINE.find(text)?.groupValues?.get(1).orEmpty()
                    val base = file.fileName.toString().substringBeforeLast('.')
                    val simple = if (kotlin) "${base.replaceFirstChar(Char::uppercaseChar)}Kt" else base
                    found += if (pkg.isEmpty()) simple else "$pkg.$simple"
                }
            }
        }
        if (found.size > 1) {
            notes += "Module '${data.name}' has more than one entry point (${found.joinToString(", ")}); " +
                "the export runs ${found.first()}."
        }
        return found.firstOrNull()
    }

    private fun sourceRootsOf(data: ModuleData, dir: Path): List<Path> =
        data.sourceSets.flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles }
            .map { dir.resolve(it.dirRelPath) }
            .filter { Files.isDirectory(it) }

    /** A Kotlin `fun main` at column 0 (a top-level one, which is the runnable shape). */
    private val TOP_LEVEL_MAIN_KT = Regex("""(?m)^fun\s+main\s*\(""")
    private val MAIN_JAVA = Regex("""static\s+void\s+main\s*\(""")
    private val PACKAGE_LINE = Regex("""(?m)^\s*package\s+([\w.]+)""")

    /** True when any source root holds a `.kt` file, which is what decides the Kotlin plugin. */
    private fun hasKotlinSources(data: ModuleData, dir: Path): Boolean =
        sourceRootsOf(data, dir).any { rootDir ->
            Files.walk(rootDir).use { stream ->
                stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            }
        }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    private fun renderFiles(
        root: Path,
        project: ProjectData,
        modules: List<Exported>,
        libraries: Map<String, LibraryData>,
        notes: MutableList<String>,
    ): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        val rootModule = modules.firstOrNull { it.isRoot }
        val android = modules.any { it.android != null }

        files["settings.gradle.kts"] = settingsScript(root, project, modules)
        files["build.gradle.kts"] = rootScript(rootModule, modules, libraries, notes)
        for (module in modules.filterNot { it.isRoot }) {
            files[module.buildFile] = moduleScript(module, modules, libraries, notes, foldedIntoRoot = false)
        }
        for (module in modules) files += extraModuleFiles(module)
        files["gradle.properties"] = gradleProperties(modules, android)
        files["gradle/wrapper/gradle-wrapper.properties"] = wrapperProperties()
        files[".gitignore"] = gitIgnore()
        // Written last: it reports on everything the passes above collected.
        files[NOTES_FILE] = notesDocument(project, modules, notes)
        return files
    }

    private fun settingsScript(root: Path, project: ProjectData, modules: List<Exported>): String {
        val k = Kts()
        k.header()
        k.block("pluginManagement") {
            block("repositories") {
                line("google()")
                line("mavenCentral()")
                line("gradlePluginPortal()")
            }
        }
        k.line()
        k.block("dependencyResolutionManagement") {
            line("repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)")
            block("repositories") {
                line("google()")
                line("mavenCentral()")
                for (repo in userRepositories(root)) line("maven(\"${escape(repo)}\")")
            }
        }
        k.line()
        k.line("rootProject.name = \"${escape(project.name)}\"")
        for (module in modules.filterNot { it.isRoot }) {
            k.line("include(\"${module.path}\")")
            // Only when sanitising a directory name changed it: then the derived path no longer names the
            // directory it came from, and Gradle has to be pointed at it.
            val implied = module.path.trim(':').replace(':', '/')
            if (implied != module.relDir) {
                k.line("project(\"${module.path}\").projectDir = file(\"${escape(module.relDir)}\")")
            }
        }
        return k.text()
    }

    /**
     * The root script: every plugin any module uses, declared once with its version and `apply false`. When
     * a module IS the root project its own script is folded in here, with the plugins it applies losing the
     * `apply false`.
     */
    private fun rootScript(
        rootModule: Exported?,
        modules: List<Exported>,
        libraries: Map<String, LibraryData>,
        notes: MutableList<String>,
    ): String {
        val applied = rootModule?.let { pluginsFor(it) }.orEmpty()
        val used = LinkedHashSet<String>()
        // Only what a subproject needs resolved from a repository: Gradle's own plugins carry no version,
        // so declaring them here would be noise (the root project's own are the exception, applied below).
        for (module in modules) used += pluginsFor(module).filter { it !in CORE_PLUGINS || it in applied }
        val k = Kts()
        k.header()
        k.block("plugins") {
            for (id in used) {
                val version = versionOf(id)?.let { " version \"$it\"" } ?: ""
                val apply = if (id in applied) "" else " apply false"
                line("id(\"$id\")$version$apply")
            }
        }
        if (rootModule == null) return k.text()
        k.line()
        // The root module's own body; its plugins are already in the merged block above.
        return k.text() + moduleScript(rootModule, modules, libraries, notes, foldedIntoRoot = true)
    }

    /** The plugin ids a module applies, in the order they belong in a `plugins` block. */
    private fun pluginsFor(module: Exported): List<String> {
        val android = module.android
        val ids = ArrayList<String>()
        if (android != null) {
            ids += if (android.isApplication) PLUGIN_APP else PLUGIN_LIB
            if (module.kotlin) ids += PLUGIN_KOTLIN_ANDROID
        } else {
            ids += PLUGIN_JAVA_LIBRARY
            if (module.kotlin) ids += PLUGIN_KOTLIN_JVM
            if (module.mainClass != null) ids += PLUGIN_APPLICATION
        }
        if (module.compose) ids += PLUGIN_COMPOSE
        if (module.parcelize) ids += PLUGIN_PARCELIZE
        if (module.serialization) ids += PLUGIN_SERIALIZATION
        return ids
    }

    private fun versionOf(id: String): String? = when {
        id in CORE_PLUGINS -> null
        id.startsWith("com.android.") -> AGP_VERSION
        else -> KOTLIN_VERSION
    }

    private fun moduleScript(
        module: Exported,
        modules: List<Exported>,
        libraries: Map<String, LibraryData>,
        notes: MutableList<String>,
        /** True when this is the root project's own module: the header and plugins are already written. */
        foldedIntoRoot: Boolean,
    ): String {
        val k = Kts()
        if (!foldedIntoRoot) {
            k.header()
            k.block("plugins") { for (id in pluginsFor(module)) line("id(\"$id\")") }
            k.line()
        }
        val android = module.android
        if (android != null) {
            androidBlock(k, module, android, notes)
        } else {
            k.block("java") {
                line("sourceCompatibility = ${javaVersion(module.data.languageLevel)}")
                line("targetCompatibility = ${javaVersion(module.data.languageLevel)}")
            }
            sourceSetsBlock(k, module)
            if (module.mainClass != null) {
                k.line()
                k.block("application") { line("mainClass.set(\"${escape(module.mainClass)}\")") }
            }
        }
        if (module.kotlin) {
            k.line()
            k.block("kotlin") {
                block("compilerOptions") {
                    line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.${jvmTarget(module.data.languageLevel)})")
                }
            }
        }
        k.line()
        nativesConfiguration(k, module)
        dependenciesBlock(k, module, modules, libraries, notes)
        nativesUnpackTask(k, module, notes)
        return k.text().trimEnd() + "\n"
    }

    /** Every `natives`-scoped declaration of [module] (the per-ABI classifier jars). */
    private fun nativesDeps(module: Exported): List<LibraryDependency> =
        module.data.dependencies.filterIsInstance<LibraryDependency>()
            .filter { it.scope == DependencyScope.NATIVES }

    /**
     * `natives` is the IDE's own scope, not a Gradle configuration, so an exported build has to create it
     * before anything can declare into it. Written only when the module actually uses it, so an ordinary
     * build script is unchanged.
     */
    private fun nativesConfiguration(k: Kts, module: Exported) {
        if (nativesDeps(module).isEmpty()) return
        k.line("// Native libraries: each classifier jar holds a bare `.so` with no `lib/<abi>/` prefix, so")
        k.line("// the ABI comes from the classifier and the jars are unpacked rather than compiled against.")
        k.block("configurations") { line("create(\"natives\")") }
        k.line()
    }

    /**
     * The counterpart of [nativesConfiguration]: unpack each resolved `natives` jar into
     * `build/unpackedNatives/<abi>/`, taking the ABI from the artifact's classifier, and register that
     * directory as a `jniLibs` source directory so the Android plugin packages it. Wiring the task provider
     * into `srcDir` is what makes the merge depend on it, with no task-ordering hook to get wrong.
     *
     * This is the copy task a libGDX-style build writes by hand; like that one it resolves the configuration
     * inside `doLast`, so it is not configuration-cache compatible (noted in the export report).
     */
    private fun nativesUnpackTask(k: Kts, module: Exported, notes: MutableList<String>) {
        if (nativesDeps(module).isEmpty()) return
        if (module.android == null) {
            notes += "Module '${module.data.name}' declares native libraries, which only an Android module " +
                "packages. They were exported into a `natives` configuration that nothing consumes."
            return
        }
        k.line()
        k.block("val unpackNatives by tasks.registering") {
            line("val outDir = layout.buildDirectory.dir(\"unpackedNatives\")")
            line("val nativeJars = configurations[\"natives\"]")
            line("inputs.files(nativeJars)")
            line("outputs.dir(outDir)")
            block("doLast") {
                line("val abis = setOf(\"armeabi-v7a\", \"arm64-v8a\", \"x86\", \"x86_64\")")
                block("nativeJars.resolvedConfiguration.resolvedArtifacts.forEach") {
                    line("artifact ->")
                    line("val abi = artifact.classifier?.removePrefix(\"natives-\")")
                    line("if (abi == null || abi !in abis) return@forEach")
                    block("copy") {
                        block("from(zipTree(artifact.file))") {
                            line("include(\"**/*.so\")")
                            // The `.so` sits at the archive root in some jars and under an ABI dir in others;
                            // flattening to the file name makes both land as `<abi>/lib*.so`.
                            line("eachFile { path = name }")
                        }
                        line("into(outDir.get().dir(abi))")
                        line("includeEmptyDirs = false")
                    }
                }
            }
        }
        k.line()
        k.block("android") {
            line("sourceSets.getByName(\"main\").jniLibs.srcDir(unpackNatives)")
        }
        notes += "Module '${module.data.name}' unpacks its native libraries with the generated " +
            "`unpackNatives` task, which resolves a configuration at execution time and so is not " +
            "configuration-cache compatible."
    }

    private fun androidBlock(k: Kts, module: Exported, facet: AndroidFacet, notes: MutableList<String>) {
        k.block("android") {
            line("namespace = \"${escape(facet.namespace)}\"")
            line("compileSdk = ${facet.compileSdk}")
            line()
            block("defaultConfig") {
                line("minSdk = ${facet.minSdk}")
                if (facet.isApplication) line("targetSdk = ${facet.targetSdk}")
                if (facet.versionCode != AndroidFacet.DEFAULT_VERSION_CODE) line("versionCode = ${facet.versionCode}")
                if (facet.versionName != AndroidFacet.DEFAULT_VERSION_NAME) {
                    line("versionName = \"${escape(facet.versionName)}\"")
                }
                placeholders(facet.manifestPlaceholders)
            }
            buildTypesBlock(this, module, facet, notes)
            flavorsBlock(this, facet)
            line()
            block("compileOptions") {
                line("sourceCompatibility = ${javaVersion(module.data.languageLevel)}")
                line("targetCompatibility = ${javaVersion(module.data.languageLevel)}")
                if (facet.coreLibraryDesugaringEnabled) line("isCoreLibraryDesugaringEnabled = true")
            }
            buildFeaturesBlock(this, module, facet)
            packagingBlock(this, facet)
            manifestAndSources(this, module, facet)
        }
    }

    private fun buildTypesBlock(k: Kts, module: Exported, facet: AndroidFacet, notes: MutableList<String>) {
        val interesting = facet.buildTypes.filterNot { isConventional(it) }
        if (interesting.isEmpty()) return
        k.line()
        k.block("buildTypes") {
            for (type in interesting) {
                val creator = if (type.name == "debug" || type.name == "release") "getByName" else "create"
                block("$creator(\"${escape(type.name)}\")") {
                    if (type.debuggable != (type.name == "debug")) line("isDebuggable = ${type.debuggable}")
                    line("isMinifyEnabled = ${type.minifyEnabled}")
                    if (type.shrinkResources) line("isShrinkResources = true")
                    val proguard = type.proguardFiles.map(::proguardArgument) +
                        (if (type.proguardRules.isEmpty()) emptyList() else listOf("\"${extraRulesFile(type)}\""))
                    if (proguard.isNotEmpty()) line("proguardFiles(${proguard.joinToString(", ")})")
                    // consumerProguardFiles is a library DSL: rules a library hands its consumers.
                    if (!facet.isApplication && type.consumerProguardFiles.isNotEmpty()) {
                        line("consumerProguardFiles(${type.consumerProguardFiles.joinToString(", ") { "\"${escape(it)}\"" }})")
                    }
                    type.applicationIdSuffix?.let { line("applicationIdSuffix = \"${escape(it)}\"") }
                    type.versionNameSuffix?.let { line("versionNameSuffix = \"${escape(it)}\"") }
                    placeholders(type.manifestPlaceholders)
                    val signing = type.signingConfig
                    if (signing != null) {
                        line("// Signed with the '${escape(signing)}' keystore in CodeAssist. Add a")
                        line("// signingConfigs block here and point this build type at it.")
                        notes += "Module '${module.data.name}' signs its '${type.name}' build with the " +
                            "'$signing' keystore, which lives in the app keystore registry rather " +
                            "than in the project. Add a signingConfigs block and your keystore to sign it with Gradle."
                    }
                }
            }
        }
        for (type in interesting.filter { it.proguardRules.isNotEmpty() }) {
            notes += "The inline R8 rules on '${type.name}' were written to ${extraRulesFile(type)} " +
                "in module '${module.data.name}', since Gradle takes rules as files."
        }
    }

    /** True when a build type says nothing AGP does not already do for a type of that name. */
    private fun isConventional(type: BuildType): Boolean =
        type.debuggable == (type.name == "debug") && !type.minifyEnabled && !type.shrinkResources &&
            type.proguardFiles.isEmpty() && type.consumerProguardFiles.isEmpty() && type.proguardRules.isEmpty() &&
            type.applicationIdSuffix == null && type.versionNameSuffix == null &&
            type.manifestPlaceholders.isEmpty() && type.signingConfig == null

    private fun proguardArgument(entry: String): String =
        if (DefaultProguardFiles.isDefault(entry)) "getDefaultProguardFile(\"${escape(entry)}\")"
        else "\"${escape(entry)}\""

    private fun extraRulesFile(type: BuildType): String = "proguard-rules-${sanitize(type.name)}.pro"

    private fun flavorsBlock(k: Kts, facet: AndroidFacet) {
        if (facet.productFlavors.isEmpty()) return
        k.line()
        if (facet.flavorDimensions.isNotEmpty()) {
            k.line("flavorDimensions += listOf(${facet.flavorDimensions.joinToString(", ") { "\"${escape(it)}\"" }})")
        }
        k.block("productFlavors") {
            for (flavor in facet.productFlavors) {
                block("create(\"${escape(flavor.name)}\")") {
                    flavor.dimension?.let { line("dimension = \"${escape(it)}\"") }
                    flavor.applicationId?.let { line("applicationId = \"${escape(it)}\"") }
                    flavor.applicationIdSuffix?.let { line("applicationIdSuffix = \"${escape(it)}\"") }
                    flavor.versionName?.let { line("versionName = \"${escape(it)}\"") }
                    placeholders(flavor.manifestPlaceholders)
                }
            }
        }
    }

    private fun buildFeaturesBlock(k: Kts, module: Exported, facet: AndroidFacet) {
        val viewBinding = facet.buildFeatures.viewBinding
        if (!viewBinding && !module.compose) return
        k.line()
        k.block("buildFeatures") {
            if (viewBinding) line("viewBinding = true")
            if (module.compose) line("compose = true")
        }
    }

    private fun packagingBlock(k: Kts, facet: AndroidFacet) {
        val packaging = facet.packaging
        if (packaging.isDefault) return
        k.line()
        k.block("packaging") {
            if (!packaging.resources.isEmpty) {
                block("resources") {
                    setOfPatterns("excludes", packaging.resources.excludes)
                    setOfPatterns("pickFirsts", packaging.resources.pickFirsts)
                    setOfPatterns("merges", packaging.resources.merges)
                }
            }
            if (!packaging.jniLibs.isEmpty) {
                block("jniLibs") {
                    setOfPatterns("excludes", packaging.jniLibs.excludes)
                    setOfPatterns("pickFirsts", packaging.jniLibs.pickFirsts)
                }
            }
        }
    }

    /** The manifest location and the source roots, emitted only where they differ from the AGP layout. */
    private fun manifestAndSources(k: Kts, module: Exported, facet: AndroidFacet) {
        val manifest = facet.manifest.trim('/')
        val custom = customRoots(module)
        if (manifest == "src/main/AndroidManifest.xml" && custom.isEmpty()) return
        val customManifest = manifest.takeIf { it != "src/main/AndroidManifest.xml" }
        k.line()
        k.block("sourceSets") {
            // "main" carries both the manifest and its roots, so it opens exactly one block either way.
            val names = (listOfNotNull(customManifest?.let { "main" }) + custom.keys).distinct()
            for (name in names) {
                block("getByName(\"${escape(name)}\")") {
                    if (name == "main" && customManifest != null) line("manifest.srcFile(\"${escape(customManifest)}\")")
                    custom[name]?.let { srcDirs(it) }
                }
            }
        }
    }

    /** The JVM counterpart of [manifestAndSources]: only the roots Gradle would not find by itself. */
    private fun sourceSetsBlock(k: Kts, module: Exported) {
        val custom = customRoots(module)
        if (custom.isEmpty()) return
        k.line()
        k.block("sourceSets") {
            for ((name, roots) in custom) block("named(\"${escape(name)}\")") { srcDirs(roots) }
        }
    }

    /**
     * Source roots that are NOT where Gradle already looks, grouped by source set. A project laid out the
     * conventional way (which every project the IDE creates is) produces nothing here, so the common build
     * file stays as short as one Android Studio writes.
     */
    private fun customRoots(module: Exported): Map<String, Map<ContentRole, List<String>>> {
        val result = LinkedHashMap<String, Map<ContentRole, List<String>>>()
        for (sourceSet in module.data.sourceSets) {
            val byRole = LinkedHashMap<ContentRole, MutableList<String>>()
            for (contentRoot in sourceSet.contentRoots) {
                val dir = contentRoot.dirRelPath.trim('/')
                for (role in contentRoot.roles) {
                    if (role !in SRC_DIR_CONTAINERS) continue
                    if (dir in defaultDirs(sourceSet.name, role)) continue
                    byRole.getOrPut(role) { ArrayList() }.add(dir)
                }
            }
            if (byRole.isNotEmpty()) result[sourceSet.name] = byRole
        }
        return result
    }

    /** Where AGP and the JVM/Kotlin plugins already look for a role in the source set named [set]. */
    private fun defaultDirs(set: String, role: ContentRole): Set<String> = when (role) {
        ContentRole.SOURCE -> setOf("src/$set/java", "src/$set/kotlin")
        ContentRole.RESOURCE -> setOf("src/$set/resources")
        ContentRole.ANDROID_RES -> setOf("src/$set/res")
        ContentRole.ASSETS -> setOf("src/$set/assets")
        ContentRole.AIDL -> setOf("src/$set/aidl")
        ContentRole.JNI_LIBS -> setOf("src/$set/jniLibs")
        else -> emptySet()
    }

    /** The roles that map onto a Gradle source-directory container; the rest (generated, excluded) do not. */
    private val SRC_DIR_CONTAINERS = mapOf(
        ContentRole.SOURCE to "java",
        ContentRole.RESOURCE to "resources",
        ContentRole.ANDROID_RES to "res",
        ContentRole.ASSETS to "assets",
        ContentRole.AIDL to "aidl",
        ContentRole.JNI_LIBS to "jniLibs",
    )

    private fun dependenciesBlock(
        k: Kts,
        module: Exported,
        modules: List<Exported>,
        libraries: Map<String, LibraryData>,
        notes: MutableList<String>,
    ) {
        val byId = modules.associateBy { it.data.id }
        k.blockIfAny("dependencies") {
            if (module.android?.coreLibraryDesugaringEnabled == true) {
                line("coreLibraryDesugaring(\"$DESUGAR_JDK_LIBS\")")
            }
            for (entry in module.data.dependencies) {
                val configuration = configurationFor(entry, module, notes)
                when (entry) {
                    is ModuleDependency -> {
                        val target = byId[entry.target.value]
                        if (target == null) {
                            notes += "Module '${module.data.name}' depends on '${entry.target.value}', which is " +
                                "not in this project. That dependency was left out."
                        } else {
                            line("$configuration(project(\"${target.path}\"))")
                        }
                    }
                    is PlatformDependency -> line("$configuration(platform(\"${escape(entry.bom.toString())}\"))")
                    is LibraryDependency -> libraryDependency(this, module, entry, configuration, libraries, notes)
                    is SdkDependency -> Unit // The platform: a Gradle plugin supplies it (compileSdk / the JDK).
                }
            }
            kspComment(this, module, notes)
        }
    }

    private fun libraryDependency(
        k: Kts,
        module: Exported,
        entry: LibraryDependency,
        configuration: String,
        libraries: Map<String, LibraryData>,
        notes: MutableList<String>,
    ) {
        val name = entry.library.name
        if (name == BUNDLED_STDLIB) {
            // The Kotlin plugin adds its own stdlib; declaring the IDE's bundled one would pin a second copy.
            return
        }
        val parts = name.split(':')
        // `group:name:version:classifier` is a valid Gradle declaration too (a module's secondary artifact),
        // so four segments are as writable as three.
        if (parts.size !in 2..4 || parts.any { it.isBlank() }) {
            // Not a Maven coordinate, so there is nothing to declare: it is a library the IDE resolved to
            // files of its own. Left as a comment on the spot it belongs, and reported.
            val jars = libraries[name]?.classes?.size ?: 0
            k.line("// $configuration(\"$name\") // no Maven coordinate; ${if (jars > 0) "$jars file(s) in CodeAssist" else "unresolved"}")
            notes += "Module '${module.data.name}' depends on the library '$name', which has no Maven " +
                "coordinate to write into a Gradle build file. Add it by hand."
            return
        }
        // A configuration the script itself created has no generated accessor, so `natives("…")` would not
        // compile. The Kotlin DSL's `"name"(notation)` form addresses a configuration by name and takes the
        // same optional configuration block.
        val declare =
            if (entry.scope.offClasspath) "\"$configuration\"(\"${escape(name)}\")"
            else "$configuration(\"${escape(name)}\")"
        if (entry.exclusions.isEmpty()) {
            k.line(declare)
            return
        }
        k.block(declare) {
            for (exclusion in entry.exclusions) {
                val group = exclusion.group.takeIf { it != "*" }
                val artifact = exclusion.name.takeIf { it != "*" }
                when {
                    group == null && artifact == null -> line("isTransitive = false")
                    group == null -> line("exclude(module = \"${escape(artifact!!)}\")")
                    artifact == null -> line("exclude(group = \"${escape(group)}\")")
                    else -> line("exclude(group = \"${escape(group)}\", module = \"${escape(artifact)}\")")
                }
            }
        }
    }

    /**
     * KSP has no faithful export: the IDE runs the processors it bundles off a classpath probe, with no
     * plugin version or processor coordinate declared anywhere in the model. Rather than guess a KSP
     * version that would fail the user's first sync, the block is written commented, with the processors
     * named, and the note says what to add.
     */
    private fun kspComment(k: Kts, module: Exported, notes: MutableList<String>) {
        val processors = module.android?.buildFeatures?.kspProcessors.orEmpty()
        if (processors.isEmpty()) return
        val named = processors.sorted().joinToString(", ")
        k.line()
        k.line("// CodeAssist ran these annotation processors from its own bundle: $named.")
        k.line("// For Gradle, apply the com.google.devtools.ksp plugin (a version matching Kotlin")
        k.line("// $KOTLIN_VERSION) and add each processor here, for example ksp(\"androidx.room:room-compiler:…\").")
        notes += "Module '${module.data.name}' uses the bundled annotation processors ($named). CodeAssist " +
            "runs those itself, so no KSP plugin or processor version exists to export. Apply the KSP plugin " +
            "and declare each processor to build them with Gradle."
    }

    /** The Gradle configuration an order entry belongs in, qualified by its build variant when it has one. */
    private fun configurationFor(entry: OrderEntry, module: Exported, notes: MutableList<String>): String {
        val base = gradleConfiguration(entry.scope)
        val variant = entry.variant ?: return base
        if (entry.scope.offClasspath) {
            notes += "A native-library dependency of module '${module.data.name}' was scoped to the " +
                "'$variant' variant, which was dropped: the exported project carries one `$base` " +
                "configuration, packaged into every variant."
            return base
        }
        if (entry.scope == DependencyScope.TEST_IMPLEMENTATION) {
            notes += "A test dependency of module '${module.data.name}' was scoped to the '$variant' variant, " +
                "which was dropped: it is declared as a plain testImplementation."
            return base
        }
        return variant + base.replaceFirstChar(Char::uppercaseChar)
    }

    // ---------------------------------------------------------------------------------------------
    // The supporting files
    // ---------------------------------------------------------------------------------------------

    /** Files a module needs beyond its build script: the R8 rules its build types name. */
    private fun extraModuleFiles(module: Exported): Map<String, String> {
        val facet = module.android ?: return emptyMap()
        val dir = module.relDir
        val files = LinkedHashMap<String, String>()
        for (type in facet.buildTypes) {
            if (type.proguardRules.isEmpty()) continue
            val text = buildString {
                appendLine("# Inline R8 rules from the '${type.name}' build type, written out by CodeAssist.")
                for (rule in type.proguardRules) appendLine(rule)
            }
            files[prefixed(dir, extraRulesFile(type))] = text
        }
        // A rules file a build type names but the project never had: AGP fails on a missing proguardFile.
        for (type in facet.buildTypes) {
            val named = type.proguardFiles + if (facet.isApplication) emptyList() else type.consumerProguardFiles
            for (entry in named) {
                if (DefaultProguardFiles.isDefault(entry)) continue
                if (Files.exists(module.dir.resolve(entry))) continue
                files[prefixed(dir, entry)] = "# Add project-specific R8 rules here.\n"
            }
        }
        return files
    }

    private fun prefixed(dir: String, rel: String): String = if (dir.isEmpty()) rel else "$dir/$rel"

    private fun gradleProperties(modules: List<Exported>, android: Boolean): String = buildString {
        appendLine("# Generated by CodeAssist.")
        appendLine("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8")
        appendLine("org.gradle.parallel=true")
        appendLine("org.gradle.caching=true")
        appendLine("kotlin.code.style=official")
        if (android) {
            appendLine("android.useAndroidX=true")
            appendLine("android.nonTransitiveRClass=true")
            // R8 full mode is AGP's default; only a project that turned it off needs the line.
            if (modules.any { it.android?.r8FullMode == false }) appendLine("android.enableR8.fullMode=false")
        }
    }

    private fun wrapperProperties(): String = buildString {
        appendLine("distributionBase=GRADLE_USER_HOME")
        appendLine("distributionPath=wrapper/dists")
        appendLine("distributionUrl=https\\://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip")
        appendLine("networkTimeout=10000")
        appendLine("validateDistributionUrl=true")
        appendLine("zipStoreBase=GRADLE_USER_HOME")
        appendLine("zipStorePath=wrapper/dists")
    }

    private fun gitIgnore(): String = buildString {
        appendLine("*.iml")
        appendLine(".gradle/")
        appendLine("build/")
        appendLine("local.properties")
        appendLine(".idea/")
        appendLine(".DS_Store")
        appendLine("captures/")
        appendLine(".cxx/")
    }

    /** The README the export leads with: what it is, how to open it, and every note the render collected. */
    private fun notesDocument(project: ProjectData, modules: List<Exported>, notes: List<String>): String =
        buildString {
            appendLine("# ${project.name}")
            appendLine()
            appendLine("Exported from CodeAssist as a Gradle project. The build files here were generated from")
            appendLine("the project model, so they are a faithful starting point rather than a build that was")
            appendLine("ever run: open it in Android Studio (or run `gradle build`) and expect to adjust it.")
            appendLine()
            appendLine("## What it was generated with")
            appendLine()
            appendLine("- Android Gradle plugin $AGP_VERSION")
            appendLine("- Kotlin $KOTLIN_VERSION")
            appendLine("- Gradle $GRADLE_VERSION (the wrapper properties are here; the `gradlew` scripts are not,")
            appendLine("  so run `gradle wrapper` once or let Android Studio do it)")
            appendLine()
            appendLine("Kotlin is pinned to the version CodeAssist compiled this project with, so the sources")
            appendLine("build the same way there. It is newer than the D8/R8 this AGP bundles, which warns")
            appendLine("about Kotlin metadata it cannot rewrite; raising the AGP version clears that.")
            appendLine()
            appendLine("## Modules")
            appendLine()
            for (module in modules) {
                val kind = when {
                    module.android?.isApplication == true -> "Android app"
                    module.android != null -> "Android library"
                    module.mainClass != null -> "JVM application"
                    else -> "JVM library"
                }
                appendLine("- `${module.path}` (${module.data.name}): $kind")
            }
            appendLine()
            appendLine("## Notes")
            appendLine()
            if (notes.isEmpty()) {
                appendLine("Everything in the project model was carried over.")
            } else {
                for (note in notes) appendLine("- $note")
            }
            appendLine()
            appendLine("The Android SDK location is not exported: Android Studio writes `local.properties`")
            appendLine("on the first sync, or set `sdk.dir` there yourself.")
        }

    // ---------------------------------------------------------------------------------------------
    // Disk
    // ---------------------------------------------------------------------------------------------

    /** The extra Maven repositories the project resolved against (`.platform/repositories.txt`). */
    private fun userRepositories(root: Path): List<String> {
        val file = root.resolve(".platform/repositories.txt")
        val text = runCatching { Files.readAllBytes(file).toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }.toList()
    }

    /** Every project file worth carrying: sources and resources, never IDE state or build output. */
    private fun sourceFiles(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { !isExcluded(root.relativize(it).toString().replace(File.separatorChar, '/')) }
                .sorted()
                // Not Stream.toList(): a JDK 16 method, missing on the older ART runtimes this ships to.
                .collect(Collectors.toList())
        }

    private fun isExcluded(rel: String): Boolean {
        if (rel == "module.toml" || rel.endsWith("/module.toml")) return true
        if (rel.endsWith(".iml")) return true
        return rel.split('/').any { it == "build" || it == ".gradle" || it == ".platform" || it == ".git" || it == ".idea" || it == "exports" }
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    // Gradle spells 8 as `1_8` and every later version bare. A level that names no Java version exports at
    // LanguageLevel.DEFAULT, which is what the exported project can actually be built with.
    private fun javaVersion(level: LanguageLevel): String =
        if (level == LanguageLevel.JAVA_8) "JavaVersion.VERSION_1_8" else "JavaVersion.VERSION_${level.javaVersion}"

    private fun jvmTarget(level: LanguageLevel): String =
        if (level == LanguageLevel.JAVA_8) "JVM_1_8" else "JVM_${level.javaVersion}"

    /** Kotlin-DSL string escaping: a build file is Kotlin source, so a quote or backslash has to survive. */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    /** A tiny Kotlin-DSL writer: it owns the indentation so the callers read like the script they emit. */
    private class Kts(private val startDepth: Int = 0) {
        private val out = StringBuilder()
        private var depth = startDepth

        fun header() {
            line("// Generated by CodeAssist from the project model. Review before you rely on it.")
            line()
        }

        fun line(text: String = "") {
            if (text.isEmpty()) {
                out.append('\n')
            } else {
                repeat(depth) { out.append("    ") }
                out.append(text).append('\n')
            }
        }

        fun block(header: String, body: Kts.() -> Unit) {
            line("$header {")
            depth++
            body()
            depth--
            line("}")
        }

        /** [block], but written only when the body produced something: no empty `dependencies { }`. */
        fun blockIfAny(header: String, body: Kts.() -> Unit) {
            val nested = Kts(depth + 1)
            nested.body()
            if (nested.out.isBlank()) return
            line("$header {")
            out.append(nested.out)
            line("}")
        }

        /** `manifestPlaceholders["k"] = "v"`, the form that works in both defaultConfig and a build type. */
        fun placeholders(values: Map<String, String>) {
            for ((key, value) in values) line("manifestPlaceholders[\"${escape(key)}\"] = \"${escape(value)}\"")
        }

        fun setOfPatterns(property: String, patterns: Set<String>) {
            if (patterns.isEmpty()) return
            line("$property += setOf(${patterns.joinToString(", ") { "\"${escape(it)}\"" }})")
        }

        fun srcDirs(roots: Map<ContentRole, List<String>>) {
            for ((role, dirs) in roots) {
                val container = SRC_DIR_CONTAINERS[role] ?: continue
                line("$container.srcDirs(${dirs.joinToString(", ") { "\"${escape(it)}\"" }})")
            }
        }

        fun text(): String = out.toString()
    }
}

/** The Gradle configuration name a model dependency scope declares into, which is what [DependencyScope.id]
 *  is: the built-ins were named after these configurations, and a plugin's own scope names its own. */
internal fun gradleConfiguration(scope: DependencyScope): String = scope.id
