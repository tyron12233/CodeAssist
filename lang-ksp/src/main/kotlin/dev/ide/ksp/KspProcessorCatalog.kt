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

    companion object {
        /** Marker class entries for the blessed catalog (the runtime each ships in). */
        const val ROOM_MARKER = "androidx/room/RoomDatabase.class"
        const val MOSHI_MARKER = "com/squareup/moshi/JsonClass.class"
        const val HILT_MARKER = "dagger/hilt/InstallIn.class"
        const val GLIDE_MARKER = "com/bumptech/glide/annotation/GlideModule.class"

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
