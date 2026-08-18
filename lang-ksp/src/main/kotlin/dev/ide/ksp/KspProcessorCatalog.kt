package dev.ide.ksp

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * A KSP processor the IDE bundles and can run on-device: identified by a **marker class** on the module's
 * compile classpath (its runtime, which the user adds like any dependency), with its processor jars supplied
 * by [jars] (bundled in the app — EXECUTED code, never downloaded, so it stays within Google Play's
 * dynamic-code-loading policy). The runtime the marker lives in is Maven-resolved (data the module compiles
 * against); only the processor is bundled.
 */
class KspProcessor(
    val id: String,
    val displayName: String,
    /** One-line description for the toggle UI. */
    val description: String,
    /**
     * A `.class` entry whose presence on the module classpath means "this processor applies" — the same
     * probe the Compose/serialization/Parcelize compiler plugins use (add the runtime, the processor turns on
     * with no extra wiring). E.g. Room's `androidx/room/RoomDatabase.class`.
     */
    val probeClassEntry: String,
    /**
     * The runtime Maven coordinate(s) that carry [probeClassEntry]. Enabling the processor's toggle adds these
     * (like a Build Feature adding its runtime), which is what trips the probe and activates the processor —
     * so the toggle works exactly like the Compose/Parcelize compiler-plugin toggles.
     */
    val runtimeCoordinates: List<String>,
    /**
     * Class entries the BUNDLED processor's generated code references that only a matching-or-newer runtime
     * carries. Dagger's generated `_Factory` classes, for instance, import `dagger.internal.Provider`, which exists only
     * in the Dagger runtime that shipped with that processor generation.
     *
     * The IDE always runs the version it bundles (executed code must ship with the app, never be downloaded),
     * so a project pinning an OLDER runtime gets generated sources its own runtime cannot compile. Probing for
     * the class is used rather than comparing version numbers: it is exactly the condition that matters, needs
     * no per-library release history, and can't reject a runtime that actually works.
     *
     * Empty ⇒ no known requirement beyond the runtime being present at all.
     */
    val requiredRuntimeClasses: List<String> = emptyList(),
    /**
     * Processor options (KSP's `-P ksp:apoption=…`) to pass whenever this processor runs: the ones a
     * processor's own **Gradle plugin** would contribute, which a project's build files never spell out and
     * this build system therefore has to supply itself. Merged across the applicable processors
     * ([KspProcessorCatalog.optionsFor]).
     */
    val options: Map<String, String> = emptyMap(),
    /** The processor's classpath (bundled in-app). Empty ⇒ the processor isn't bundled in this build → skipped. */
    val jars: () -> List<Path>,
)

/**
 * Maps a module's compile classpath to the bundled KSP processors that apply. This is the **probe-based
 * activation** the rest of the IDE already uses for compiler plugins: a module opts into Room by adding
 * `room-runtime` (Dependencies screen), the probe fires, and [KspSourceGenerator] runs the bundled
 * `room-compiler` on our own compiler — no per-processor toggle or model change.
 *
 * Injected as the `processors` resolver of [KspSourceGenerator]: `{ req -> catalog.classpathFor(req.classpath) }`.
 */
class KspProcessorCatalog(val processors: List<KspProcessor>) {

    /** The bundled processors whose runtime marker is on [classpath] — marker-only, ignoring how the runtime
     *  got there. Used for UI/display probes; build-time activation goes through the declared-aware overload
     *  ([applicable] with `declaredDependencies`) so a merely-transitive runtime never activates a processor. */
    fun applicable(classpath: List<Path>): List<KspProcessor> =
        processors.filter { classpathHasClass(classpath, it.probeClassEntry) }

    /**
     * The bundled processors that should RUN for a module: those whose runtime is a **directly-declared**
     * dependency ([declaredDependencies], `group:name`) AND whose marker is present on [classpath]. Gating on
     * the declared set (not the transitive classpath) is the AGP-faithful rule — KSP runs a processor only on
     * an explicit opt-in. A project that merely reaches a processor's runtime transitively (e.g. JetSnack pulls
     * `room-runtime` through another library but never declares Room) must NOT run that processor: doing so is
     * both wrong (no Room is used) and, for processors with a native toolchain like Room's SQLite verifier,
     * would crash the build on a device that lacks that native library.
     */
    fun applicable(classpath: List<Path>, declaredDependencies: List<String>): List<KspProcessor> {
        val declared = declaredDependencies.mapNotNull { groupName(it) }.toSet()
        if (declared.isEmpty()) return emptyList()
        return processors.filter { p ->
            p.runtimeCoordinates.mapNotNull { groupName(it) }.any { it in declared } &&
                classpathHasClass(classpath, p.probeClassEntry)
        }
    }

    /** The union of applicable (marker-only) processors' bundled jars. Prefer the declared-aware overload for
     *  build-time activation. */
    fun classpathFor(classpath: List<Path>): List<Path> =
        applicable(classpath).flatMap { it.jars() }.filter { Files.exists(it) }

    /** The union of the RUN-eligible processors' bundled jars (declared-aware; skips any not bundled here). */
    fun classpathFor(classpath: List<Path>, declaredDependencies: List<String>): List<Path> =
        applicable(classpath, declaredDependencies).flatMap { it.jars() }.filter { Files.exists(it) }

    /** The merged [KspProcessor.options] of the RUN-eligible processors: the processor options a project
     *  would otherwise get from each library's Gradle plugin. Later processors win a (never expected) clash. */
    fun optionsFor(classpath: List<Path>, declaredDependencies: List<String>): Map<String, String> =
        applicable(classpath, declaredDependencies).fold(LinkedHashMap()) { acc, p -> acc.putAll(p.options); acc }

    /**
     * A RUN-eligible processor whose generated code the module's declared runtime is too OLD to compile:
     * [missing] are the [KspProcessor.requiredRuntimeClasses] absent from the module's classpath, and
     * [declared] the module's own coordinates for that runtime (as declared, version included when it has one).
     */
    class RuntimeMismatch(
        val processor: KspProcessor,
        val missing: List<String>,
        val declared: List<String> = emptyList(),
    ) {
        /** The missing classes as source-level names, for a human-readable message. */
        val missingTypeNames: List<String> get() = missing.map { it.removeSuffix(".class").replace('/', '.') }

        /** The coordinates the runtime has to be set to: the versions the bundled processor was built against. */
        val requiredCoordinates: List<String> get() = processor.runtimeCoordinates

        /** A build-console line that names the symbol, the cause, and the exact coordinate to change. */
        val message: String
            get() = "ksp: ${processor.displayName}: the bundled processor generates code referencing " +
                missingTypeNames.joinToString() +
                ", which this module's runtime does not provide. The IDE always runs the processor version it " +
                "bundles, so the runtime has to match. Set " +
                requiredCoordinates.joinToString { versionHint(it) } + ", then rebuild."

        /** `group:name:version` as the instruction to give the user: "`group:name` to `version`". */
        private fun versionHint(coordinate: String): String {
            val version = coordinate.substringAfterLast(':', "")
            val groupName = groupName(coordinate) ?: return coordinate
            return if (version.isBlank() || version == groupName.substringAfter(':')) coordinate
            else "$groupName to $version"
        }
    }

    /**
     * What a preflight found for a module: [blocking] problems fail source generation, [warnings] are reported
     * and generation proceeds. A mismatch the user has explicitly ACCEPTED moves from the first list to the
     * second: they have been told the build will fail downstream and chose to go ahead, so the IDE keeps saying
     * so once per build without standing in the way.
     */
    class Preflight(val blocking: List<String> = emptyList(), val warnings: List<String> = emptyList())

    /**
     * The [Preflight] for a module, with [accepted] the processor ids whose mismatch the user has accepted
     * (persisted per module). Blocking unless accepted.
     */
    fun preflight(
        classpath: List<Path>,
        declaredDependencies: List<String>,
        accepted: Set<String> = emptySet(),
    ): Preflight {
        val (waived, blocking) = runtimeMismatches(classpath, declaredDependencies)
            .partition { it.processor.id in accepted }
        return Preflight(
            blocking = blocking.map { it.message },
            warnings = waived.map { "${it.message} (accepted for this module: building anyway)" },
        )
    }

    /**
     * The RUN-eligible processors whose runtime is too old for the code they would generate. Reported as a
     * build failure BEFORE any processor runs. Otherwise generation "succeeds" and the module fails later with
     * one unresolved-symbol error per generated file, which points at the generated code rather than the
     * version skew that caused it.
     *
     * Deliberately not folded into [applicable]: silently skipping the processor would replace those errors
     * with "cannot find symbol `Foo_Factory`" at every INJECTION SITE, which is even further from the cause.
     */
    fun runtimeMismatches(classpath: List<Path>, declaredDependencies: List<String>): List<RuntimeMismatch> =
        applicable(classpath, declaredDependencies).mapNotNull { p ->
            p.requiredRuntimeClasses.filterNot { classpathHasClass(classpath, it) }
                .takeIf { it.isNotEmpty() }
                ?.let { missing ->
                    val wanted = p.runtimeCoordinates.mapNotNull { groupName(it) }.toSet()
                    RuntimeMismatch(p, missing, declaredDependencies.filter { groupName(it) in wanted })
                }
        }

    companion object {
        /** Marker class entries for the blessed catalog (the runtime each ships in). */
        const val ROOM_MARKER = "androidx/room/RoomDatabase.class"
        const val MOSHI_MARKER = "com/squareup/moshi/JsonClass.class"
        const val HILT_MARKER = "dagger/hilt/InstallIn.class"
        const val GLIDE_MARKER = "com/bumptech/glide/annotation/GlideModule.class"

        /**
         * The processor option Hilt's **Gradle plugin** sets, and which Hilt's processor requires before it
         * will read an `@AndroidEntryPoint` class's base type from the class's own `extends` clause.
         *
         * Without it the processor insists the annotation carry an explicit base: `@AndroidEntryPoint` with
         * no value fails the build with "Expected @AndroidEntryPoint to have a value. Did you forget to apply
         * the Gradle Plugin?", which is exactly the setup every Hilt project written for AGP has.
         *
         * The option is a promise that something rewrites each annotated class to extend the generated
         * `Hilt_` sibling; in an AGP build that is the plugin's bytecode transform, here it is
         * `transformHiltClasses` (`dev.ide.android.support.tools.HiltEntryPoints`). The two must ship
         * together: the option alone turns a build error into an app that silently never injects anything.
         */
        val HILT_OPTIONS = mapOf("dagger.hilt.android.internal.disableAndroidSuperclassValidation" to "true")

        /**
         * The blessed catalog (Room, Moshi, Hilt/Dagger, Glide). Each entry's [KspProcessor.jars] is supplied
         * by [bundledJars] — keyed by processor id — so the host wires in whatever it bundles (e.g. an
         * extracted asset dir); an id with no bundled jars is simply skipped. Keeping the id→jars mapping
         * host-injected keeps this module free of any packaging/asset assumptions.
         */
        /** The blessed catalog sourced from the in-app [BundledKspProcessors] — the production wiring
         *  (`ide-core` contributes `KspSourceGenerator(processors = { bundledCatalog.classpathFor(it.classpath) })`).
         *  An id whose bundle isn't packaged in this build resolves to empty and is skipped. */
        fun bundled(): KspProcessorCatalog = blessed { BundledKspProcessors.jarsFor(it) }

        fun blessed(bundledJars: (id: String) -> List<Path> = { emptyList() }): KspProcessorCatalog =
            KspProcessorCatalog(
                listOf(
                    KspProcessor(
                        "room", "Room", "Generate @Database/@Dao implementations for the Room persistence library.",
                        ROOM_MARKER, listOf("androidx.room:room-runtime:2.8.4"),
                    ) { bundledJars("room") },
                    KspProcessor(
                        "moshi", "Moshi", "Generate JSON adapters for @JsonClass(generateAdapter = true) classes.",
                        MOSHI_MARKER, listOf("com.squareup.moshi:moshi:1.15.2"),
                    ) { bundledJars("moshi") },
                    KspProcessor(
                        "hilt", "Hilt / Dagger", "Generate Hilt/Dagger dependency-injection components.",
                        HILT_MARKER, listOf("com.google.dagger:hilt-android:2.60.1"),
                        // Dagger's generated `_Factory` classes import `dagger.internal.Provider`, which the
                        // bundled 2.60.1 runtime has and a pre-2.5x one does not (that generation's generated
                        // code used `javax.inject.Provider`). A project pinning an older Hilt otherwise builds
                        // to "The import dagger.internal.Provider cannot be resolved" in every generated file.
                        requiredRuntimeClasses = listOf("dagger/internal/Provider.class"),
                        options = HILT_OPTIONS,
                    ) { bundledJars("hilt") },
                    KspProcessor(
                        "glide", "Glide", "Generate Glide's GlideApp/module API from @GlideModule.",
                        GLIDE_MARKER, listOf("com.github.bumptech.glide:glide:5.0.9"),
                    ) { bundledJars("glide") },
                ),
            )

        /** `group:name` from a `group:name[:version[:classifier]]` coordinate, or null when it isn't one. */
        internal fun groupName(coordinate: String): String? {
            val parts = coordinate.split(':')
            return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) "${parts[0]}:${parts[1]}" else null
        }

        /** True when [classpath] carries [classEntry] (a jar entry or a file under a class dir). Cheap; stops
         *  at the first hit. Mirrors `SerializationCompilerPlugin.usesSerialization`. */
        fun classpathHasClass(classpath: List<Path>, classEntry: String): Boolean = classpath.any { entry ->
            when {
                !Files.exists(entry) -> false
                Files.isDirectory(entry) -> Files.exists(entry.resolve(classEntry))
                entry.toString().endsWith(".jar") || entry.toString().endsWith(".zip") -> runCatching {
                    ZipFile(entry.toFile()).use { it.getEntry(classEntry) != null }
                }.getOrDefault(false)
                else -> false
            }
        }
    }
}
