package dev.ide.android.support

import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.ResourceModel
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField

/**
 * Contributes the light `R` class for an Android module — `<namespace>.R` with a nested class per
 * resource type (`layout`, `string`, `id`, `drawable`, …), each an `int` field per resource — generated
 * from the module's merged [dev.ide.android.support.resources.ResourceRepository] (its own `res/` plus its
 * android-lib dependencies'). So `R.layout.activity_main` resolves iff that resource actually exists. No
 * aapt2, no build: a fast, SDK-free stand-in for completion + analysis, parsed by the [ResourceModel] port.
 */
class AndroidRClassProvider(private val model: ResourceModel = ResourceModel.DEFAULT) : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank()) return emptyList()

        val repo = AndroidResources.repository(context.module, context.workspace, model)
        if (repo.isEmpty()) return emptyList()

        val nested = repo.types().sortedBy { it.rClass }.map { type ->
            SyntheticClass(
                fqName = "${facet.namespace}.R.${type.rClass}",
                fields = repo.names(type).sorted().map { SyntheticField(it) },
            )
        }
        return listOf(SyntheticClass(fqName = "${facet.namespace}.R", nestedClasses = nested, doc = "Resource identifiers (synthetic R)"))
    }
}
