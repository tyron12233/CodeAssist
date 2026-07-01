package dev.ide.android.support

import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.RIdAssignment
import dev.ide.android.support.resources.ResourceModel
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticModifier

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
class AndroidRClassProvider(
    /** Supplies the module's merged [ResourceRepository] (or null). The IDE injects a cache-backed supplier so
     *  the repository is parsed ONCE and shared across the R class, layout preview, and reference resolution -
     *  parsing it per call (per completion/analysis pass) re-read every dependency `res/` file and OOM'd. */
    private val repository: (Module, Workspace) -> ResourceRepository?,
) : SyntheticClassProvider {

    /** Standalone/default: parse the module's resources directly through [model] (tests and hosts without a
     *  shared cache). */
    constructor(model: ResourceModel = ResourceModel.DEFAULT) : this({ m, w -> AndroidResources.repository(m, w, model) })

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank()) return emptyList()

        val repo = repository(context.module, context.workspace) ?: return emptyList()
        if (repo.isEmpty()) return emptyList()
        val ids = RIdAssignment(repo)

        val nested = repo.types().sortedBy { it.rClass }.map { type ->
            if (type == ResourceType.STYLEABLE) styleableClass(facet.namespace, repo, ids)
            else SyntheticClass(
                fqName = "${facet.namespace}.R.${type.rClass}",
                modifiers = NESTED_MODIFIERS,
                fields = repo.names(type).sorted().map { name ->
                    // Field name is the aapt2-sanitized identifier (e.g. style `Theme.App` → `Theme_App`); the
                    // id lookup keeps the raw resource name. User code references the sanitized name.
                    SyntheticField(fieldName(name), constant = hex(ids.id(type, name)))
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
                name = fieldName(styleable),
                type = "int[]",
                constant = array.joinToString(prefix = "{ ", postfix = " }") { hex(it) }.let { if (array.isEmpty()) "{}" else it },
            )
            // A styleable's child <attr> may reference a framework attr by its prefixed name (`android:textColor`);
            // aapt2 names the index constant `<Styleable>_android_textColor`, so sanitize the attr too — an
            // unsanitized `:` (or `.`) makes the generated R.java fail to compile ("Syntax error on token ':'").
            attrs.forEachIndexed { index, attr ->
                fields += SyntheticField("${fieldName(styleable)}_${fieldName(attr)}", constant = index.toString())
            }
        }
        return SyntheticClass(fqName = "$namespace.R.styleable", modifiers = NESTED_MODIFIERS, fields = fields)
    }

    private fun hex(id: Int?): String = if (id == null) "0" else "0x%08x".format(id)

    /** aapt2's R field-name sanitization: a resource/attr name → a valid Java identifier (`.`/`:` → `_`). */
    private fun fieldName(name: String): String = name.replace('.', '_').replace(':', '_')

    private companion object {
        // Nested R subclasses must be `public static final` — like real R.java. Without STATIC they are inner
        // (non-static) classes, where a static field that isn't a constant variable is illegal below Java 16;
        // R.styleable's `int[]` arrays are exactly that, so the layout-preview compile (pinned to source 8 to
        // feed android.jar as -bootclasspath) rejects the synthetic R and no custom view renders.
        val NESTED_MODIFIERS = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC, SyntheticModifier.FINAL)
    }
}
