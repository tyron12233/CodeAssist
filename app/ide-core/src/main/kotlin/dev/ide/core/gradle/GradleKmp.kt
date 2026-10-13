package dev.ide.core.gradle

import dev.ide.core.sdk.JdkManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Reads a Kotlin Multiplatform module's `kotlin { }` block: which targets it declares, which source sets feed
 * the one target the import models, and the `dependencies { }` blocks of those source sets.
 *
 * A CodeAssist module is one compilation and a KMP module is several, so the import picks ONE target per
 * module and collapses the source sets feeding it (`commonMain`, any intermediate set the platform set
 * `dependsOn`, and the platform set itself) into that compilation. It is the same shape `generateNativeModel`
 * exports (see [GradleModelImporter]), read from the script text instead of from a configured Gradle model.
 *
 * Which target, for a module declaring both an Android and a JVM one: the project's [preferredPlatform], which
 * the user picks and which defaults to the host's own kind (Android on a phone, the JVM on the desktop). A module
 * declaring only one of them gets that one, and a module with neither (iOS/JS/Wasm only) keeps its common code.
 */
object GradleKmp {

    const val PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"

    enum class Platform { ANDROID, JVM, COMMON_ONLY }

    /** One source set the import models. [common] marks the sets above the platform one, which the compiler
     *  must be told about for an `expect` and its `actual` to compile together. */
    data class SourceSet(val name: String, val common: Boolean, val test: Boolean)

    data class Spec(
        val platform: Platform,
        /** Main sets common-first with the platform set last, then the test sets. */
        val sourceSets: List<SourceSet>,
        /** The `dependencies { }` bodies of the modeled sets, each paired with whether its set is a test set. */
        val dependencyBodies: List<Pair<String, Boolean>>,
        /** Targets declared but not modeled (`iosArm64`, `js`, the JVM target when Android won), for the notes. */
        val skippedTargets: List<String>,
        /** The Android configuration AGP's KMP library plugin reads from inside `kotlin { }` (`androidLibrary { }`,
         *  or `android { }` in newer AGP), which holds the namespace and SDK levels. */
        val androidConfig: String?,
        /** The platforms this module could be imported as: more than one means [preferredPlatform] decided. */
        val available: Set<Platform> = emptySet(),
    )

    /** Where a project's choice is kept: IDE-owned, beside the import marker, never in the user's scripts. */
    const val PREFERENCE_FILE = ".platform/kmp-target"

    /** The platform modules declaring both an Android and a JVM target are imported for. */
    fun preferredPlatform(root: Path): Platform = readPreference(root) ?: hostDefault()

    /** The user's explicit choice, or null when they have not made one. */
    fun readPreference(root: Path): Platform? {
        val file = root.resolve(PREFERENCE_FILE)
        if (!Files.isRegularFile(file)) return null
        val id = runCatching { file.readText().trim() }.getOrNull() ?: return null
        return Platform.values().firstOrNull { it != Platform.COMMON_ONLY && it.name.equals(id, ignoreCase = true) }
    }

    fun setPreference(root: Path, platform: Platform) {
        require(platform != Platform.COMMON_ONLY) { "only ANDROID or JVM can be preferred" }
        val file = root.resolve(PREFERENCE_FILE)
        Files.createDirectories(file.parent)
        file.writeText(platform.name.lowercase() + "\n")
    }

    /** Android on a phone, where APKs are what gets built and run; the JVM on the desktop, where a desktop app
     *  runs in place. */
    fun hostDefault(): Platform =
        if (JdkManager.isAndroidRuntime(System.getProperty("java.vm.name"), System.getProperty("java.vendor"))) Platform.ANDROID
        else Platform.JVM

    private val ANDROID_TARGETS = setOf("androidTarget", "android", "androidLibrary")

    private val NON_JVM_TARGET = Regex(
        """\b(ios\w+|macos\w+|watchos\w+|tvos\w+|linux\w+|mingw\w+|androidNative\w+|js|wasmJs|wasmWasi)\s*[({]"""
    )

    /** `jvm()`, `jvm("desktop")`, `jvm(name = "desktop")`, `jvm { … }`. */
    private val HEAD = Regex("""^(\w+)\s*(?:\(([^)]*)\))?""")

    /** The name a source-set statement configures: `val x by getting|creating`, `getByName("x")`, or `x…`. */
    private val SET_BY_DELEGATE = Regex("""^val\s+(\w+)\s+by\s+(?:getting|creating)\b""")
    private val SET_BY_CALL = Regex("""^(?:getByName|create|maybeCreate|named|register)\s*\(\s*["'](\w+)["']""")

    private val DEPENDS_ON = Regex(
        """dependsOn\s*\(\s*(?:sourceSets\s*\[\s*["'](\w+)["']\s*]|(?:getByName|named)\s*\(\s*["'](\w+)["']\s*\)|(\w+))"""
    )

    /**
     * The KMP view of [build] (a comment-stripped module script). When the module declares both an Android
     * and a JVM target, [preferred] picks; Android additionally needs the module to apply an Android Gradle
     * plugin ([androidPluginApplied]), without which Gradle could not build that target either.
     */
    fun parse(build: String, androidPluginApplied: Boolean, preferred: Platform = Platform.ANDROID): Spec {
        val kotlin = GradleScript.childBlocks(build).firstOrNull { it.name == "kotlin" }?.body.orEmpty()

        var android = false
        var androidConfig: String? = null
        var jvmName: String? = null
        val setStatements = ArrayList<String>()
        for (st in GradleScript.statements(kotlin)) {
            if (st.startsWith("sourceSets.")) {
                setStatements += st.removePrefix("sourceSets.")
                continue
            }
            val head = HEAD.find(st) ?: continue
            val name = head.groupValues[1]
            when {
                name == "sourceSets" -> braceBody(st)?.let { setStatements += GradleScript.statements(it) }
                name in ANDROID_TARGETS -> {
                    android = true
                    // `androidTarget { }` configures compilations; the other two carry namespace/SDK levels.
                    if (name != "androidTarget") braceBody(st)?.let { androidConfig = it }
                }
                name == "jvm" -> jvmName = GradleScript.firstQuoted(head.groupValues[2]) ?: "jvm"
            }
        }
        val skipped = NON_JVM_TARGET.findAll(kotlin).map { it.groupValues[1] }.distinct().toMutableList()

        val canAndroid = android && (androidPluginApplied || jvmName == null)
        val platform = when {
            canAndroid && (preferred == Platform.ANDROID || jvmName == null) -> Platform.ANDROID
            jvmName != null -> Platform.JVM
            else -> Platform.COMMON_ONLY
        }
        if (platform == Platform.ANDROID && jvmName != null) skipped.add(0, jvmName)
        if (platform == Platform.JVM && android) skipped.add(0, "android")

        val sets = SourceSetGraph(setStatements)
        val (leafMain, leafTest) = when (platform) {
            Platform.ANDROID -> "androidMain" to "androidUnitTest"
            Platform.JVM -> "${jvmName}Main" to "${jvmName}Test"
            Platform.COMMON_ONLY -> null to null
        }

        val main = if (leafMain == null) listOf(SourceSet(COMMON_MAIN, common = false, test = false))
        else (sets.ancestors(leafMain, COMMON_MAIN).map { SourceSet(it, common = true, test = false) } +
            SourceSet(leafMain, common = false, test = false))
        val test = listOfNotNull(COMMON_TEST, leafTest).map { SourceSet(it, common = false, test = true) }
        val modeled = main + test

        return Spec(
            platform = platform,
            sourceSets = modeled,
            dependencyBodies = modeled.flatMap { set -> sets.dependencyBodies(set.name).map { it to set.test } },
            skippedTargets = skipped,
            androidConfig = androidConfig,
            available = buildSet {
                if (canAndroid) add(Platform.ANDROID)
                if (jvmName != null) add(Platform.JVM)
            },
        )
    }

    /** The `dependsOn` edges and dependency blocks declared in `sourceSets { }`, by set name. */
    private class SourceSetGraph(statements: List<String>) {
        private val parents = HashMap<String, LinkedHashSet<String>>()
        private val deps = HashMap<String, MutableList<String>>()

        init {
            for (st in statements) {
                val name = SET_BY_DELEGATE.find(st)?.groupValues?.get(1)
                    ?: SET_BY_CALL.find(st)?.groupValues?.get(1)
                    ?: HEAD.find(st)?.groupValues?.get(1)?.takeIf { it != "val" }
                    ?: continue
                for (m in DEPENDS_ON.findAll(st)) {
                    val parent = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                    parents.getOrPut(name) { LinkedHashSet() }.add(parent)
                }
                val body = braceBody(st) ?: continue
                // `commonMain.dependencies { … }`: the statement's own block IS the dependency block.
                val depsBody = if (Regex("""\.\s*dependencies\s*$""").containsMatchIn(st.substringBefore('{').trim())) body
                else GradleScript.blockBody(body, "dependencies")
                depsBody?.let { deps.getOrPut(name) { ArrayList() }.add(it) }
            }
        }

        /**
         * Every set [leaf] depends on, transitively, ordered so a set comes after the sets it depends on, and
         * always starting with [root]: a platform set with no `dependsOn` of its own still sees `commonMain`.
         */
        fun ancestors(leaf: String, root: String): List<String> {
            val out = LinkedHashSet<String>()
            fun visit(name: String, seen: Set<String>) {
                for (p in parents[name].orEmpty()) {
                    if (p in seen) continue
                    visit(p, seen + p)
                    out.add(p)
                }
            }
            visit(leaf, setOf(leaf))
            out.remove(leaf)
            return listOf(root) + (out - root)
        }

        fun dependencyBodies(name: String): List<String> = deps[name].orEmpty()
    }

    /** The text between a statement's first `{` and its last `}`, or null when it has no block. */
    private fun braceBody(statement: String): String? {
        val open = statement.indexOf('{')
        val close = statement.lastIndexOf('}')
        return if (open in 0 until close) statement.substring(open + 1, close) else null
    }

    const val COMMON_MAIN = "commonMain"
    const val COMMON_TEST = "commonTest"
}
