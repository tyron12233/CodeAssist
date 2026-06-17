package dev.ide.android.support

import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.RIdAssignment
import dev.ide.android.support.resources.ResourceModel
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField

/**
 * Contributes the light `R` class for an Android module — `<namespace>.R` with a nested class per resource
 * type (`layout`, `string`, `id`, `drawable`, …), each an `int` field per resource — generated from the
 * module's merged [dev.ide.android.support.resources.ResourceRepository] (its own `res/` plus its android-lib
 * dependencies'). So `R.layout.activity_main` resolves iff that resource actually exists. No aapt2, no build:
 * a fast, SDK-free stand-in for completion + analysis, parsed by the [ResourceModel] port.
 *
 * Fields carry **stable, deterministic int ids** ([RIdAssignment]), and `R.styleable.<Name>` is emitted as a
 * real `int[]` (the attr ids in declaration order) plus the `<Name>_<attr>` index constants, so a custom
 * view's `obtainStyledAttributes(attrs, R.styleable.MyChart, …)` compiles to the same ints the layout
 * preview's bridge maps back from at runtime.
 */
class AndroidRClassProvider(private val model: ResourceModel = ResourceModel.DEFAULT) : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank()) return emptyList()

        val repo = AndroidResources.repository(context.module, context.workspace, model)
        if (repo.isEmpty()) return emptyList()
        val ids = RIdAssignment(repo)

        val nested = repo.types().sortedBy { it.rClass }.map { type ->
            if (type == ResourceType.STYLEABLE) styleableClass(facet.namespace, repo, ids)
            else SyntheticClass(
                fqName = "${facet.namespace}.R.${type.rClass}",
                fields = repo.names(type).sorted().map { name ->
                    SyntheticField(name, constant = hex(ids.id(type, name)))
                },
            )
        }
        return listOf(SyntheticClass(fqName = "${facet.namespace}.R", nestedClasses = nested, doc = "Resource identifiers (synthetic R)"))
    }

    /** `R.styleable`: each `<declare-styleable>` becomes an `int[]` of its attr ids + per-attr index constants. */
    private fun styleableClass(namespace: String, repo: dev.ide.android.support.resources.ResourceRepository, ids: RIdAssignment): SyntheticClass {
        val fields = ArrayList<SyntheticField>()
        for (styleable in repo.names(ResourceType.STYLEABLE).sorted()) {
            val attrs = repo.styleableAttrs(styleable)
            val array = ids.styleableArray(repo, styleable)
            fields += SyntheticField(
                name = styleable,
                type = "int[]",
                constant = array.joinToString(prefix = "{ ", postfix = " }") { hex(it) }.let { if (array.isEmpty()) "{}" else it },
            )
            attrs.forEachIndexed { index, attr ->
                fields += SyntheticField("${styleable}_$attr", constant = index.toString())
            }
        }
        return SyntheticClass(fqName = "$namespace.R.styleable", fields = fields)
    }

    private fun hex(id: Int?): String = if (id == null) "0" else "0x%08x".format(id)
}
