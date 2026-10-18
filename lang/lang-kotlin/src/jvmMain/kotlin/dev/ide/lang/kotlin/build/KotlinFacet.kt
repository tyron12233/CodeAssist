package dev.ide.lang.kotlin.build

import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey

/**
 * Per-module Kotlin compilation settings, as the `[kotlin]` table of `module.toml`.
 *
 * It exists for [commonSourceRoots], which cannot be inferred from anything else the model holds and
 * decides whether an imported multiplatform module compiles at all, for [optIns], and for a multiplatform
 * module's [targets].
 */
data class KotlinFacet(
    /**
     * The module's COMMON source roots, relative to the module directory: `src/commonMain/kotlin` and any
     * intermediate source set above the platform one.
     *
     * A CodeAssist module is a single compilation, while a Kotlin Multiplatform module is several; importing
     * one collapses the source sets that feed a single target (`commonMain` + `androidMain`, plus whatever
     * sits between them) into that one compilation. K2 accepts exactly this, but only when it is told which
     * of the sources form the common fragment: multiplatform mode alone still rejects the pair with
     * "'expect' and corresponding 'actual' are declared in the same module".
     *
     * Empty (the default) is an ordinary single-platform module, compiled with no multiplatform flags at all.
     */
    val commonSourceRoots: List<String> = emptyList(),
    /**
     * Opt-in requirement markers applied module-wide (`-opt-in=<fqName>`), as Gradle's
     * `languageSettings { optIn("…") }` or `compilerOptions { optIn.add("…") }` declares them.
     */
    val optIns: List<String> = emptyList(),
    /**
     * Every target of a Kotlin Multiplatform module, not only the one it builds: the module's live source sets,
     * dependencies and facets are [activeTarget]'s, and each target records what it adds on top of [shared], so the
     * module can be switched to another target natively (no build scripts to re-read) and a project converted from
     * Gradle keeps all of them. Empty for a module that is not multiplatform, or one imported before targets were
     * recorded.
     */
    val targets: List<KmpTarget> = emptyList(),
    /** The [KmpTarget.name] the module is currently shaped for. */
    val activeTarget: String? = null,
    /** What every target compiles: the common roots, the common test roots, and the common Compose resources. */
    val shared: KmpShared = KmpShared(),
) : Facet {

    /** The target the module is shaped for, when it records targets. */
    val active: KmpTarget? get() = targets.firstOrNull { it.name == activeTarget } ?: targets.firstOrNull()

    /** True when this module compiles as one multiplatform compilation. */
    val multiplatform: Boolean get() = commonSourceRoots.isNotEmpty()

    override val key: FacetKey<KotlinFacet> get() = KEY

    companion object {
        val KEY = FacetKey<KotlinFacet>("kotlin")
    }
}

/**
 * Round-trips [KotlinFacet] through the `[kotlin]` table. Like the Android codec, a default-valued field is
 * an absent key rather than a written default, so an encoded facet and a saved-then-reloaded one compare
 * equal and an untouched module gains no table.
 */
/**
 * One target of a multiplatform module, as what it adds to [KotlinFacet.shared].
 *
 * Roots are module-relative and encoded `path|role,role` (a bare path is a source root, see [KmpRoots]);
 * dependencies are encoded by the host that applies the target (`scope[@variant] lib|module|platform:ref`), and
 * [facets] holds the tables this target owns while it is NOT active (an Android target's `[android]`), so
 * switching away and back loses nothing.
 */
data class KmpTarget(
    val name: String,
    /** `android` or `jvm`: which pipeline builds it. */
    val platform: String,
    /** The module type while this target is active (`android-app`, `android-lib`, `java-lib`). */
    val typeId: String,
    val roots: List<String> = emptyList(),
    val testRoots: List<String> = emptyList(),
    /** Intermediate source roots above this target that compile as common code (`src/jvmShared/kotlin`). */
    val commonRoots: List<String> = emptyList(),
    val composeResourceRoots: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val facets: Map<String, Map<String, Any?>> = emptyMap(),
)

/** What every target of a multiplatform module compiles (see [KotlinFacet.shared]). */
data class KmpShared(
    val roots: List<String> = emptyList(),
    val testRoots: List<String> = emptyList(),
    val composeResourceRoots: List<String> = emptyList(),
)

/** The `path|role,role` encoding of a content root, shared by the facet and whatever applies it. */
object KmpRoots {
    fun encode(path: String, roles: Collection<String>): String =
        if (roles.isEmpty() || roles.toList() == listOf("source")) path else "$path|${roles.joinToString(",")}"

    fun decode(text: String): Pair<String, List<String>> {
        val i = text.lastIndexOf('|')
        return if (i < 0) text to listOf("source") else text.substring(0, i) to text.substring(i + 1).split(',').filter { it.isNotBlank() }
    }
}

object KotlinFacetCodec : FacetCodec<KotlinFacet> {
    override val key get() = KotlinFacet.KEY
    override val tomlTable = "kotlin"

    override fun encode(facet: KotlinFacet): Map<String, Any?> = buildMap {
        if (facet.commonSourceRoots.isNotEmpty()) put("commonSourceRoots", facet.commonSourceRoots)
        if (facet.optIns.isNotEmpty()) put("optIns", facet.optIns)
        if (facet.targets.isNotEmpty()) {
            facet.activeTarget?.let { put("activeTarget", it) }
            put("shared", buildMap {
                putList("roots", facet.shared.roots)
                putList("testRoots", facet.shared.testRoots)
                putList("composeResourceRoots", facet.shared.composeResourceRoots)
            })
            put("targets", facet.targets.map { t ->
                buildMap {
                    put("name", t.name)
                    put("platform", t.platform)
                    put("type", t.typeId)
                    putList("roots", t.roots)
                    putList("testRoots", t.testRoots)
                    putList("commonRoots", t.commonRoots)
                    putList("composeResourceRoots", t.composeResourceRoots)
                    putList("dependencies", t.dependencies)
                    if (t.facets.isNotEmpty()) put("facets", t.facets)
                }
            })
        }
    }

    private fun MutableMap<String, Any?>.putList(key: String, values: List<String>) {
        if (values.isNotEmpty()) put(key, values)
    }

    override fun decode(values: Map<String, Any?>): KotlinFacet = KotlinFacet(
        commonSourceRoots = (values["commonSourceRoots"] as? List<*>).orEmpty().mapNotNull { it as? String },
        optIns = (values["optIns"] as? List<*>).orEmpty().mapNotNull { it as? String },
        targets = (values["targets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { t ->
            KmpTarget(
                name = t["name"] as? String ?: return@mapNotNull null,
                platform = t["platform"] as? String ?: "jvm",
                typeId = t["type"] as? String ?: "java-lib",
                roots = t.strings("roots"),
                testRoots = t.strings("testRoots"),
                commonRoots = t.strings("commonRoots"),
                composeResourceRoots = t.strings("composeResourceRoots"),
                dependencies = t.strings("dependencies"),
                facets = (t["facets"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
                    val table = k as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    table to ((v as? Map<String, Any?>) ?: return@mapNotNull null)
                }.toMap(),
            )
        },
        activeTarget = values["activeTarget"] as? String,
        shared = (values["shared"] as? Map<*, *>).orEmpty().let { s ->
            KmpShared(s.strings("roots"), s.strings("testRoots"), s.strings("composeResourceRoots"))
        },
    )

    private fun Map<*, *>.strings(key: String): List<String> = (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }
}
