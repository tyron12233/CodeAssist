package dev.ide.android.support

import dev.ide.android.support.resources.AndroidResources
import dev.ide.android.support.resources.RIdAssignment
import dev.ide.android.support.resources.ResourceItem
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
 * Also emits one `R` per dependency AAR package (`androidx.core.R`, `com.google.android.material.R`, …),
 * sliced out of the same merged repository by the `res/` dir each resource came from. An AAR ships no `R`
 * classes of its own, so this is the only thing that makes the package-qualified form non-transitive R classes
 * require — `com.google.android.material.R.attr.colorPrimary` — resolve before a build.
 *
 * Fields carry **stable, deterministic int ids** ([RIdAssignment]), and `R.styleable.<Name>` is emitted as a
 * real `int[]` (the attr ids in declaration order) plus the `<Name>_<attr>` index constants, so a custom
 * view's `obtainStyledAttributes(attrs, R.styleable.MyChart, …)` compiles to the same ints the layout
 * preview's bridge maps back from at runtime.
 */
class AndroidRClassProvider(
    /** Supplies the module's merged [ResourceRepository] (or null). The repository is parsed ONCE and shared
     *  across the R class, layout preview, and reference resolution - parsing it per call (per completion/
     *  analysis pass) re-read every dependency `res/` file and OOM'd. */
    private val repository: (Module, Workspace) -> ResourceRepository?,
) : SyntheticClassProvider {

    /**
     * The default: prefer the shared cache the workspace being asked about publishes
     * ([ANDROID_RESOURCE_REPOSITORY]), and parse directly through [model] when there is none, which is the
     * case for a standalone host or a test with no engine behind it.
     *
     * The cache is resolved through the [Workspace] that arrives with each call rather than through any
     * "currently open project" handle, so the answer always comes from the model the caller is working on.
     */
    constructor(model: ResourceModel = ResourceModel.DEFAULT) : this({ m, w ->
        w.serviceOrNull(ANDROID_RESOURCE_REPOSITORY)?.repository(m, w)
            ?: AndroidResources.repository(m, w, model)
    })

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank()) return emptyList()

        val repo = repository(context.module, context.workspace) ?: return emptyList()
        if (repo.isEmpty()) return emptyList()
        // ONE id assignment for the whole merged table, shared by every R emitted here — so
        // `com.google.android.material.R.attr.x` and the module's own `R.attr.x` are the same int, exactly as
        // they are after the app's single aapt2 link.
        val ids = RIdAssignment(repo)

        val own = rClass(facet.namespace, repo, ids)
        // Each dependency AAR's own `R`, sliced out of the merged repository by the `res/` dir its resources
        // came from. AARs ship no `R` classes (the consumer generates them from `R.txt`), so without this
        // `com.google.android.material.R.attr.colorPrimary` — the form non-transitive R classes require —
        // resolves nowhere, even though the resource itself is already in this repository.
        val aarPackages = runCatching { AndroidResources.aarResourcePackages(context.module, context.workspace) }
            .getOrDefault(emptyList())
            .filter { it.packageName != facet.namespace }
        if (aarPackages.isEmpty()) return listOf(own)
        val byResDir = itemsByResourceRoot(repo)
        val aars = aarPackages.mapNotNull { aar ->
            byResDir[aar.resDir.normalize()]?.let { rClass(aar.packageName, slice(repo, it), ids) }
        }
        return listOf(own) + aars
    }

    /**
     * [repo]'s items grouped by the `res/` root each came from. A resource file always sits exactly two levels
     * under its root (`res/<config-dir>/<file>`), so the root is the source's grandparent — one pass over the
     * items rather than a prefix test per AAR per item.
     */
    private fun itemsByResourceRoot(repo: ResourceRepository): Map<java.nio.file.Path, List<ResourceItem>> =
        repo.all().groupBy { it.source?.parent?.parent?.normalize() }
            .filterKeys { it != null }
            .mapKeys { (dir, _) -> dir!! }

    /**
     * One AAR's own resources as a repository of their own — its `R` holds exactly what that AAR declares,
     * never the consuming module's (non-transitive R). Styleable attr lists are read back from the merged
     * [repo], where a `<declare-styleable>`'s children were recorded.
     */
    private fun slice(repo: ResourceRepository, items: List<ResourceItem>): ResourceRepository {
        val styleables = items.filter { it.type == ResourceType.STYLEABLE }
            .associate { it.name to repo.styleableAttrs(it.name) }
        return ResourceRepository(items, styleableAttrs = styleables)
    }

    /** The `<namespace>.R` for [repo]'s resources, with ids from [ids] (which may span a wider table). */
    private fun rClass(namespace: String, repo: ResourceRepository, ids: RIdAssignment): SyntheticClass {
        val nested = repo.types().sortedBy { it.rClass }.map { type ->
            if (type == ResourceType.STYLEABLE) styleableClass(namespace, repo, ids)
            else SyntheticClass(
                fqName = "$namespace.R.${type.rClass}",
                modifiers = NESTED_MODIFIERS,
                fields = repo.names(type).sorted().map { name ->
                    // Field name is the aapt2-sanitized identifier (e.g. style `Theme.App` → `Theme_App`); the
                    // id lookup keeps the raw resource name. User code references the sanitized name.
                    SyntheticField(fieldName(name), constant = hex(ids.id(type, name)))
                },
            )
        }
        return SyntheticClass(fqName = "$namespace.R", nestedClasses = nested, doc = "Resource identifiers (synthetic R)")
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
