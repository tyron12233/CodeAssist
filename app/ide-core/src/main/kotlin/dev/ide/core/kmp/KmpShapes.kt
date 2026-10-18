package dev.ide.core.kmp

import dev.ide.build.jvm.buildconfig.KotlinBuildConfigFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacetCodec
import dev.ide.lang.kotlin.build.KmpRoots
import dev.ide.lang.kotlin.build.KmpShared
import dev.ide.lang.kotlin.build.KmpTarget
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalSourceSet

/**
 * A Kotlin Multiplatform module in the native model: one module, several targets, shaped for one at a time.
 *
 * A CodeAssist module is one compilation, so a multiplatform module is built for one target (Android or the
 * desktop JVM). The `[kotlin]` facet records all of them ([KotlinFacet.targets]): what every target shares, and
 * what each adds. Two operations, both pure over the importer's [ExternalModule] shapes so every caller (the
 * Gradle and Amper importers, switching a native module, the native template, New Module) goes through the same
 * code:
 *
 *  * [describe] takes the module as shaped for each target and records the targets in its facet.
 *  * [materialize] takes a module (as currently shaped) and returns it shaped for another target.
 */
object KmpShapes {

    /** The [KmpTarget.platform] values. */
    const val ANDROID = "android"
    const val JVM = "jvm"

    /** One target's name and platform, plus the module as shaped for it. */
    data class Shaped(val name: String, val platform: String, val module: ExternalModule)

    /**
     * [shapes] (one per target, the active one included) folded into the active module with every target
     * recorded in its `[kotlin]` facet. Roots and dependencies all targets have are shared; each target keeps the
     * rest, and the facet tables only it has (an Android target's `[android]`). With fewer than two targets there
     * is nothing to switch between, and the active shape is returned as it is.
     */
    fun describe(shapes: List<Shaped>, active: String): ExternalModule {
        val activeShape = shapes.firstOrNull { it.name == active } ?: shapes.first()
        if (shapes.size < 2) return activeShape.module

        val mains = shapes.associate { it.name to roots(it.module, MAIN) }
        val tests = shapes.associate { it.name to roots(it.module, TEST) }
        val deps = shapes.associate { it.name to it.module.dependencies.map(::encodeDependency) }
        val composeRoots = shapes.associate { it.name to composeRoots(it.module) }
        val tables = shapes.associate { it.name to it.module.facets.map { f -> f.table }.toSet() }

        fun <T> common(per: Map<String, List<T>>): List<T> =
            per.values.reduce { acc, list -> acc.filter { it in list } }

        val sharedMain = common(mains)
        val sharedTest = common(tests)
        val sharedDeps = common(deps)
        val sharedCompose = common(composeRoots)
        val sharedTables = tables.values.reduce { acc, set -> acc intersect set }

        val targets = shapes.map { s ->
            val kotlin = kotlinFacetOf(s.module)
            val generated = setOf(ComposeResourcesFacet.GENERATED_KOTLIN_ROOT, KotlinBuildConfigFacet.GENERATED_KOTLIN_ROOT)
            KmpTarget(
                name = s.name,
                platform = s.platform,
                typeId = s.module.typeId,
                roots = mains.getValue(s.name) - sharedMain.toSet(),
                testRoots = tests.getValue(s.name) - sharedTest.toSet(),
                commonRoots = kotlin.commonSourceRoots.filter { root ->
                    root !in generated && sharedMain.none { KmpRoots.decode(it).first == root }
                },
                composeResourceRoots = composeRoots.getValue(s.name) - sharedCompose.toSet(),
                dependencies = deps.getValue(s.name) - sharedDeps.toSet(),
                facets = s.module.facets.filter { it.table !in sharedTables }.associate { it.table to it.values },
            )
        }
        val activeKotlin = kotlinFacetOf(activeShape.module)
        val facet = activeKotlin.copy(
            targets = targets,
            activeTarget = activeShape.name,
            shared = KmpShared(sharedMain, sharedTest, sharedCompose),
        )
        return activeShape.module.copy(facets = activeShape.module.facets.filter { it.table != KotlinFacetCodec.tomlTable } + kotlinTable(facet))
    }

    /**
     * [module] (shaped for its facet's active target) reshaped for [target]: its type, its main and test roots, its
     * dependencies (the shared ones kept, the old target's swapped for the new one's), the facet tables the old
     * target owned put away and the new one's brought back, the common roots and the Compose resource roots
     * recomputed. Null when the module records no such target.
     */
    fun materialize(module: ExternalModule, target: String): ExternalModule? {
        val facet = kotlinFacetOf(module)
        val next = facet.targets.firstOrNull { it.name == target } ?: return null
        val current = facet.active

        // Dependencies: everything but the current target's own, then the new target's.
        val currentDeps = current?.dependencies.orEmpty().toSet()
        val dependencies = module.dependencies.filter { encodeDependency(it) !in currentDeps } +
            next.dependencies.mapNotNull(::decodeDependency)

        // Facets: the current target's own tables go back into its record with their latest values (the record
        // keeps them while the target is active too, which is how the tables it owns stay known), and the new
        // target's are brought out of its record.
        val ownedByCurrent = current?.facets?.keys.orEmpty()
        val putAway = module.facets.filter { it.table in ownedByCurrent }.associate { it.table to it.values }
        val targets = facet.targets.map { t ->
            if (t.name == current?.name) t.copy(facets = t.facets + putAway) else t
        }
        val composeRootsNow = facet.shared.composeResourceRoots + next.composeResourceRoots
        val kept = module.facets.filter { it.table !in ownedByCurrent && it.table != KotlinFacetCodec.tomlTable }
            .map { f ->
                if (f.table != ComposeResourcesFacetCodec.tomlTable) f
                else f.copy(values = f.values + ("roots" to composeRootsNow))
            }
        val generated = buildList {
            if (kept.any { it.table == ComposeResourcesFacetCodec.tomlTable }) add(ComposeResourcesFacet.GENERATED_KOTLIN_ROOT)
            if (kept.any { it.table == "kotlinBuildConfig" }) add(KotlinBuildConfigFacet.GENERATED_KOTLIN_ROOT)
        }
        val commonSourceRoots = facet.shared.roots.map(KmpRoots::decode)
            .filter { (_, roles) -> "source" in roles }.map { it.first } + next.commonRoots + generated
        val kotlin = facet.copy(commonSourceRoots = commonSourceRoots, activeTarget = next.name, targets = targets)

        val sourceSets = buildList {
            add(sourceSet(MAIN, DependencyScope.IMPLEMENTATION, facet.shared.roots + next.roots))
            val test = facet.shared.testRoots + next.testRoots
            if (test.isNotEmpty()) add(sourceSet(TEST, DependencyScope.TEST_IMPLEMENTATION, test))
            // Generated roots (the Res accessors, the BuildConfig object) are not target-specific.
            addAll(module.sourceSets.filter { it.name != MAIN && it.name != TEST })
        }
        return module.copy(
            typeId = next.typeId,
            sourceSets = sourceSets,
            dependencies = dependencies,
            facets = kept + next.facets.map { (table, values) -> ExternalFacet(table, values) } + kotlinTable(kotlin),
        )
    }

    // ---------------------------------------------------------------------------------------------------------

    private const val MAIN = "main"
    private const val TEST = "test"

    private fun roots(module: ExternalModule, set: String): List<String> =
        module.sourceSets.firstOrNull { it.name == set }?.roots.orEmpty()
            .map { (path, roles) -> KmpRoots.encode(path, roles.map { it.id }) }

    private fun sourceSet(name: String, scope: DependencyScope, roots: List<String>) = ExternalSourceSet(
        name, scope,
        roots.associateTo(LinkedHashMap()) { encoded ->
            val (path, roles) = KmpRoots.decode(encoded)
            path to roles.map(::ContentRole).toSet()
        },
    )

    private fun composeRoots(module: ExternalModule): List<String> =
        (module.facets.firstOrNull { it.table == ComposeResourcesFacetCodec.tomlTable }?.values?.get("roots") as? List<*>)
            .orEmpty().mapNotNull { it as? String }

    private fun kotlinFacetOf(module: ExternalModule): KotlinFacet =
        module.facets.firstOrNull { it.table == KotlinFacetCodec.tomlTable }?.let { KotlinFacetCodec.decode(it.values) }
            ?: KotlinFacet()

    private fun kotlinTable(facet: KotlinFacet) = ExternalFacet(KotlinFacetCodec.tomlTable, KotlinFacetCodec.encode(facet))

    /** `scope[@variant] lib:coordinate`, `… module:name`, `… platform:bom`. Exclusions do not survive a switch. */
    fun encodeDependency(d: ExternalDependency): String {
        val head = d.scope.name + (d.variant?.let { "@$it" } ?: "")
        return when (d) {
            is ExternalLibrary -> "$head lib:${d.coordinate}"
            is ExternalModuleRef -> "$head module:${d.moduleName}"
            is ExternalPlatform -> "$head platform:${d.bom}"
        }
    }

    fun decodeDependency(text: String): ExternalDependency? {
        val head = text.substringBefore(' ')
        val body = text.substringAfter(' ', "")
        val scope = runCatching { DependencyScope.valueOf(head.substringBefore('@')) }.getOrNull() ?: return null
        val variant = head.substringAfter('@', "").ifEmpty { null }
        return when {
            body.startsWith("lib:") -> ExternalLibrary(body.removePrefix("lib:"), scope, variant)
            body.startsWith("module:") -> ExternalModuleRef(body.removePrefix("module:"), scope, variant)
            body.startsWith("platform:") -> Coordinate.parseOrNull(body.removePrefix("platform:"))?.let { ExternalPlatform(it, scope, variant) }
            else -> null
        }
    }
}
