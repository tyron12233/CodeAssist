package dev.ide.android.support

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField

/**
 * The light `BuildConfig` for an Android module — `<namespace>.BuildConfig` with the standard generated
 * fields (`DEBUG`, `APPLICATION_ID`, `BUILD_TYPE`, `FLAVOR`, `VERSION_CODE`, `VERSION_NAME`). Like the
 * synthetic `R`, it resolves for completion + analysis before a build (values are placeholders — only the
 * field names/types matter for resolution). `APPLICATION_ID` comes from the module's [AndroidFacet].
 */
class AndroidBuildConfigProvider : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank()) return emptyList()
        val fields = listOf(
            SyntheticField("DEBUG", "boolean", constant = "true"),
            SyntheticField("APPLICATION_ID", "String", constant = "\"${facet.namespace}\""),
            SyntheticField("BUILD_TYPE", "String", constant = "\"debug\""),
            SyntheticField("FLAVOR", "String", constant = "\"\""),
            SyntheticField("VERSION_CODE", "int", constant = "1"),
            SyntheticField("VERSION_NAME", "String", constant = "\"1.0\""),
        )
        return listOf(SyntheticClass("${facet.namespace}.BuildConfig", fields = fields, doc = "Build configuration (synthetic)"))
    }
}
