package dev.ide.build.jvm.compose

import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey

/**
 * A module's Compose Multiplatform resources, as the `[composeResources]` table of `module.toml`.
 *
 * Compose resources are not Android resources and not JVM resources: a `composeResources/` tree is read at
 * runtime through a generated `Res` object whose accessors carry the qualifiers (language, region, density,
 * theme) each file was declared under. The Compose Gradle plugin generates that object; nothing else does,
 * so a module built without it compiles its own `import <package>.Res` to an unresolved reference. This
 * facet is what tells CodeAssist's build to generate it instead (see [ComposeResourcesPlugin]).
 */
data class ComposeResourcesFacet(
    /** Package of the generated `Res` object: the Gradle plugin's `packageOfResClass`. */
    val packageName: String,
    /** `publicResClass`: true makes `Res` and its accessors `public`, false keeps them `internal`. */
    val publicResClass: Boolean = false,
    /**
     * The `composeResources` roots, relative to the module directory, in the order their source sets feed
     * the compilation (`src/commonMain/composeResources`, then a platform one). A resource declared in more
     * than one root is taken from the last, matching how Compose layers a platform source set over common.
     */
    val roots: List<String> = emptyList(),
) : Facet {
    override val key: FacetKey<ComposeResourcesFacet> get() = KEY

    companion object {
        val KEY = FacetKey<ComposeResourcesFacet>("composeResources")

        /**
         * Where the generated `Res` class and its accessors are written, relative to the module directory.
         * An importer declares it as a SOURCE content root, so it compiles with the module's hand-written
         * code.
         *
         * Deliberately outside `generateSources`' own output directory, and deliberately not declared as the
         * module's GENERATED root: that task empties its output on every run (so a stale generated file
         * cannot survive the deletion of what produced it), and a second producer writing there has its
         * work deleted by the first source generator to run.
         */
        const val GENERATED_KOTLIN_ROOT: String = "build/compose-resources/kotlin"

        /**
         * Where the generated resource tree is staged, relative to the module directory. It is a RESOURCE
         * content root rather than an output the plugin writes straight into the packaged classes, so it
         * travels through `processResources` like any other resource and is packaged by the JVM and Android
         * pipelines alike, with no special case in either. An importer that declares this facet declares
         * both roots alongside it.
         */
        const val GENERATED_RESOURCES_ROOT: String = "build/compose-resources/resources"

        /** Sub-directory of the staged tree and of the runtime lookup path: `composeResources/<package>/…`. */
        const val RESOURCE_DIR: String = "composeResources"
    }
}

/** Round-trips [ComposeResourcesFacet] through the `[composeResources]` table. */
object ComposeResourcesFacetCodec : FacetCodec<ComposeResourcesFacet> {
    override val key get() = ComposeResourcesFacet.KEY
    override val tomlTable = "composeResources"

    override fun encode(facet: ComposeResourcesFacet): Map<String, Any?> = buildMap {
        put("package", facet.packageName)
        if (facet.publicResClass) put("public", true)
        if (facet.roots.isNotEmpty()) put("roots", facet.roots)
    }

    override fun decode(values: Map<String, Any?>): ComposeResourcesFacet = ComposeResourcesFacet(
        packageName = values["package"] as? String ?: "",
        publicResClass = values["public"] as? Boolean ?: false,
        roots = (values["roots"] as? List<*>).orEmpty().mapNotNull { it as? String },
    )
}
