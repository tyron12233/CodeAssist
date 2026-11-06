package dev.ide.core.services

import dev.ide.analysis.Codes
import dev.ide.model.Coordinate
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiAddResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * The library a user means to use but has not added: an `import` that does not resolve, a class typed by name
 * that nothing on the classpath has, an XML layout tag naming a library view. Behind the editor's
 * "Add library …" fixes and the "this file uses N libraries that aren't added" notice.
 *
 * Which library: [LibraryCatalog] first (it covers AndroidX, which nothing can search by class), then Maven
 * Central's class search for an import the catalog does not know. That search is asynchronous: the first time
 * an import is seen the lookup starts and no fix is offered, and the answer (cached for the session) is there
 * the next time the editor asks, a moment later.
 *
 * Which version: the newest stable one the module can build with ([versionFor]).
 */
internal class MissingLibraries(private val deps: Host, private val scope: CoroutineScope) {

    /** What this needs of the dependency service (an interface so tests can stand in for it). */
    interface Host {
        suspend fun publishedVersions(group: String, name: String): List<String>
        suspend fun artifactsWithClass(fqn: String): List<Coordinate>?
        suspend fun fetchAar(coordinate: Coordinate): Path?
        fun moduleNamed(name: String): Module?
        fun isAndroidModule(module: Module): Boolean
        fun declaredUnresolved(module: Module): List<String>
        suspend fun addDependency(moduleName: String, coordinate: String, scope: String): UiAddResult
        suspend fun retryDependencyResolution()
    }

    private val log = Log.logger("deps.missing")

    /** A library to add: catalog-known, or found by class search. */
    data class Library(
        /** `group:name`. */
        val coordinate: String,
        val label: String,
        /** The version to fall back on (offline), or to use as is when [pinned]. */
        val version: String,
        val pinned: Boolean = false,
        val versionSuffix: String? = null,
        val test: Boolean = false,
    ) {
        val group: String get() = coordinate.substringBefore(':')
        val name: String get() = coordinate.substringAfter(':')
    }

    /** What an unresolved diagnostic asks for. */
    sealed class Wanted {
        /** An `import` (Java, Kotlin) of [fqn]. */
        data class Import(val fqn: String) : Wanted()
        /** A class typed by its simple [name], with no import. */
        data class SimpleName(val name: String) : Wanted()
        /** An XML element naming the view class [fqn]. */
        data class Tag(val fqn: String) : Wanted()
    }

    /** One missing library and what the file uses from it. */
    data class Need(val library: Library, val wanted: Wanted, val importFqn: String?)

    // ---- what a diagnostic wants -----------------------------------------------------------------------

    /**
     * Read what an unresolved diagnostic ([code], [message], range `[start, end)` in [text]) wants, or null when
     * it is not about a missing class: an `import` line names the class outright (the diagnostic covers only
     * part of it, often just the package that does not exist), an XML unknown-tag error names its tag, and
     * anything else is the reference itself.
     */
    fun wanted(code: String?, message: String, text: CharSequence, start: Int, end: Int): Wanted? {
        if (code == XML_UNKNOWN_TAG) {
            val tag = TAG_IN_MESSAGE.find(message)?.groupValues?.get(1) ?: return null
            return if ('.' in tag) Wanted.Tag(tag) else null
        }
        if (code != Codes.UNRESOLVED_REFERENCE && code != KOTLIN_UNRESOLVED) return null
        val s = start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (s - 1).coerceAtLeast(0)).let { if (s == 0 || it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd).toString().trim()
        if (line.startsWith("import ")) {
            val fqn = importTarget(line) ?: return null
            return Wanted.Import(fqn)
        }
        val name = text.subSequence(s, end.coerceIn(s, text.length)).toString().trim().removeSurrounding("`")
        if (name.isEmpty() || !name[0].isUpperCase() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        return Wanted.SimpleName(name)
    }

    /** The class an import line names: `import static a.b.C.d;` → `a.b.C.d`, `import a.b.C as D` → `a.b.C`. */
    fun importTarget(line: String): String? {
        val body = line.removePrefix("import").trim().removePrefix("static ").trim()
            .substringBefore(';').substringBefore(" as ").substringBefore("//").trim()
            .replace("`", "").removeSuffix(".*")
        return body.takeIf { FQN.matches(it) && '.' in it }
    }

    /**
     * The library behind [wanted] for a module that is or is not [android], or null when none is known (yet:
     * an import the catalog does not cover starts an online lookup that a later call will see).
     */
    fun libraryFor(wanted: Wanted, android: Boolean): Library? = when (wanted) {
        is Wanted.SimpleName -> LibraryCatalog.forSimpleName(wanted.name, android)?.first?.let(::fromEntry)
        is Wanted.Import -> LibraryCatalog.forFqn(wanted.fqn, android)?.let(::fromEntry) ?: online(wanted.fqn, android)
        is Wanted.Tag -> LibraryCatalog.forFqn(wanted.fqn, android)?.let(::fromEntry) ?: online(wanted.fqn, android)
    }

    /** The class a simple-name fix should import along with adding its library. */
    fun importFor(wanted: Wanted, android: Boolean): String? =
        (wanted as? Wanted.SimpleName)?.let { LibraryCatalog.forSimpleName(it.name, android)?.second }

    private fun fromEntry(e: LibraryCatalog.Entry) =
        Library(e.coordinate, e.label, e.version, e.pinned, e.versionSuffix, e.test)

    // ---- the online lookup -----------------------------------------------------------------------------

    private val onlineAnswers = ConcurrentHashMap<String, Library>()
    private val onlineMisses = ConcurrentHashMap.newKeySet<String>()
    private val onlineInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Fires when an online lookup finds a library, so the editor can ask again. */
    private val _onlineFound = MutableStateFlow(0)
    val onlineFound: StateFlow<Int> get() = _onlineFound

    private fun online(fqn: String, android: Boolean): Library? {
        if (!searchable(fqn)) return null
        onlineAnswers[fqn]?.let { return it }
        if (fqn in onlineMisses || !onlineInFlight.add(fqn)) return null
        scope.launch {
            try {
                val hits = deps.artifactsWithClass(fqn)
                if (hits == null) return@launch   // no host answered: ask again next time
                val best = bestHit(fqn, hits)
                if (best == null) onlineMisses += fqn
                else {
                    onlineAnswers[fqn] = Library("${best.group}:${best.name}", best.name, best.version)
                    _onlineFound.value++
                }
            } catch (e: Exception) {
                log.debug("class search for $fqn failed: ${e.message}")
            } finally {
                onlineInFlight -= fqn
            }
        }
        return null
    }

    // ---- versions --------------------------------------------------------------------------------------

    /**
     * The version of [library] to add to [module]: the newest stable release it can build with. For an
     * Android module that means checking each candidate's `.aar` (downloaded once, then cached for the add
     * itself) against the module's minSdk (the AAR manifest's `minSdkVersion`) and compileSdk
     * (`aar-metadata.properties`' `minCompileSdk`), stepping down one minor line at a time. A jar library
     * imposes neither. Offline, the catalog's known-good version.
     */
    suspend fun versionFor(library: Library, module: Module): String {
        if (library.pinned) return library.version
        val stable = deps.publishedVersions(library.group, library.name).filter { isStable(it, library.versionSuffix) }
        if (stable.isEmpty()) return library.version
        val facet = module.facets.get(dev.ide.android.support.AndroidFacet.KEY)
        if (facet == null || !deps.isAndroidModule(module)) return stable.first()
        val minSdk = facet.minSdk
        val compileSdk = facet.compileSdk
        for (version in newestPerMinorLine(stable).take(MAX_VERSION_CHECKS)) {
            val aar = deps.fetchAar(Coordinate(library.group, library.name, version)) ?: return version
            val needs = aarRequirements(aar)
            if ((needs.minSdk ?: 0) <= minSdk && (needs.minCompileSdk ?: 0) <= compileSdk) return version
            log.info("${library.coordinate}:$version needs minSdk ${needs.minSdk} / compileSdk ${needs.minCompileSdk}; module has $minSdk / $compileSdk")
        }
        return library.version
    }

    data class AarRequirements(val minSdk: Int?, val minCompileSdk: Int?)

    // ---- the project side ------------------------------------------------------------------------------

    /** Whether [module] already declares [library] (any version). */
    fun declares(module: Module, library: Library): Boolean =
        module.dependencies.filterIsInstance<LibraryDependency>().any { dep ->
            val c = Coordinate.parseOrNull(dep.library.name)
            c != null && c.group == library.group && c.name == library.name
        }

    /** Whether a declared [library] has not resolved: the fix then retries resolution rather than adding it twice. */
    fun declaredButUnresolved(module: Module, library: Library): Boolean =
        deps.declaredUnresolved(module).any { it.startsWith("${library.coordinate}:") }

    /**
     * Add [libraries] to [moduleName], each at [versionFor], to the test configuration when [testSource] or the
     * library is a test library. One result for the lot.
     */
    suspend fun add(moduleName: String, libraries: List<Library>, testSource: Boolean): UiAddResult {
        val module = deps.moduleNamed(moduleName) ?: return UiAddResult(false, "No module '$moduleName'.")
        val added = ArrayList<String>()
        val failed = ArrayList<String>()
        for (lib in libraries.distinctBy { it.coordinate }) {
            if (declares(module, lib)) continue
            val version = runCatching { versionFor(lib, module) }.getOrDefault(lib.version)
            val scopeLabel = if (testSource || lib.test) "testImplementation" else "implementation"
            val result = deps.addDependency(moduleName, "${lib.coordinate}:$version", scopeLabel)
            if (result.success) added += "${lib.label} $version" else failed += result.message
        }
        val message = buildString {
            if (added.isNotEmpty()) append("Added ").append(added.joinToString(", ")).append('.')
            if (failed.isNotEmpty()) { if (isNotEmpty()) append(' '); append(failed.joinToString(" ")) }
        }
        return UiAddResult(failed.isEmpty(), message.ifEmpty { "Nothing to add." }, added.size).also {
            _lastOutcome.value = it
        }
    }

    /** Retry resolving what the module already declares. */
    suspend fun resolveDeclared() = deps.retryDependencyResolution()

    private val _lastOutcome = MutableStateFlow<UiAddResult?>(null)

    /** The outcome of the last add started from an editor fix, for the editor to report a failure. */
    val lastOutcome: StateFlow<UiAddResult?> get() = _lastOutcome

    fun clearOutcome() { _lastOutcome.value = null }

    /** Run [add] in the background: a fix applies its import edit at once and the library follows. */
    fun addInBackground(moduleName: String, libraries: List<Library>, testSource: Boolean) {
        scope.launch {
            runCatching { add(moduleName, libraries, testSource) }
                .onFailure { _lastOutcome.value = UiAddResult(false, "Could not add ${libraries.joinToString { it.label }}: ${it.message}") }
        }
    }

    fun resolveInBackground() {
        scope.launch { runCatching { resolveDeclared() } }
    }

    companion object {
        const val KOTLIN_UNRESOLVED = "kt.unresolved"
        const val XML_UNKNOWN_TAG = "android.unknownTag"
        val CODES = setOf(Codes.UNRESOLVED_REFERENCE, KOTLIN_UNRESOLVED, XML_UNKNOWN_TAG)

        private const val MAX_VERSION_CHECKS = 5
        private val FQN = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*""")
        private val TAG_IN_MESSAGE = Regex("""<([A-Za-z_][\w.$]*)>""")
        private val UNSTABLE = Regex("""(?i)(alpha|beta|rc|cr|snapshot|preview|eap|dev|milestone|-m\d)""")

        /** The JDK, the Android framework, Kotlin's own library: an unresolved import there is a typo, not a library. */
        private val NOT_LIBRARIES = listOf("java.", "javax.annotation.processing", "android.", "kotlin.", "dalvik.", "sun.", "jdk.", "com.android.internal.")

        fun searchable(fqn: String): Boolean =
            fqn.count { it == '.' } >= 2 && NOT_LIBRARIES.none { fqn.startsWith(it) }

        fun isStable(version: String, suffix: String?): Boolean {
            if (suffix != null && !version.endsWith(suffix)) return false
            val core = if (suffix != null) version.removeSuffix(suffix) else version
            return !UNSTABLE.containsMatchIn(core)
        }

        /** The newest version of each `major.minor` line, newest line first (input newest-first). */
        fun newestPerMinorLine(newestFirst: List<String>): List<String> {
            val seen = HashSet<String>()
            return newestFirst.filter { seen.add(it.split('.', '-').take(2).joinToString(".")) }
        }

        /**
         * The best of [hits] for class [fqn]: the artifact whose group shares the most leading segments with the
         * class's package, then not a repackaged / sources / bundle flavor, then the shortest name.
         */
        fun bestHit(fqn: String, hits: List<Coordinate>): Coordinate? {
            if (hits.isEmpty()) return null
            val pkg = fqn.split('.')
            fun shared(group: String): Int = group.split('.').zip(pkg).takeWhile { (a, b) -> a == b }.count()
            return hits.filter { h -> ODD_FLAVORS.none { it in h.name.lowercase() } }
                .ifEmpty { hits }
                .sortedWith(compareByDescending<Coordinate> { shared(it.group) }.thenBy { it.name.length })
                .first()
        }

        private val ODD_FLAVORS = listOf("shaded", "sources", "bundle", "uber", "-all", "relocated", "repackaged")

        /** The minSdk and minCompileSdk an `.aar` declares. Absent ones impose nothing. */
        fun aarRequirements(aar: Path): AarRequirements = runCatching {
            ZipFile(aar.toFile()).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
                val meta = zip.getEntry(AAR_METADATA)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
                AarRequirements(
                    minSdk = manifest?.let { MIN_SDK.find(it)?.groupValues?.get(1)?.toIntOrNull() },
                    minCompileSdk = meta?.let { MIN_COMPILE_SDK.find(it)?.groupValues?.get(1)?.toIntOrNull() },
                )
            }
        }.getOrDefault(AarRequirements(null, null))

        private const val AAR_METADATA = "META-INF/com/android/build/gradle/aar-metadata.properties"
        private val MIN_SDK = Regex("""android:minSdkVersion\s*=\s*"(\d+)"""")
        private val MIN_COMPILE_SDK = Regex("""(?m)^\s*minCompileSdk\s*=\s*(\d+)""")
    }
}
