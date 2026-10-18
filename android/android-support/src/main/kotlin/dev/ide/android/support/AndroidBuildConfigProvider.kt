package dev.ide.android.support

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField

/**
 * The light `BuildConfig` for an Android module: the same fields the build generates for the module's default
 * variant ([BuildConfigs.fields]), including its `buildConfigField`s, so they resolve and complete before a
 * build. A module with `buildConfig = false` gets none, as the build gives it none.
 */
class AndroidBuildConfigProvider : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (facet.namespace.isBlank() || !facet.buildFeatures.buildConfig) return emptyList()
        val variant = AndroidVariants.defaultVariant(context.module) ?: return emptyList()
        val fields = BuildConfigs.fields(facet, variant, facet.versionCode, facet.versionName).map { f ->
            SyntheticField(f.name, f.type, constant = constantOf(f), doc = f.comment)
        }
        return listOf(SyntheticClass("${facet.namespace}.BuildConfig", fields = fields, doc = "Build configuration (synthetic)"))
    }

    /** A literal the editor can show as the constant's value; an arbitrary expression has none to show. */
    private fun constantOf(f: BuildConfigs.Field): String? = when {
        f.name == "DEBUG" -> Regex("""parseBoolean\("(true|false)"\)""").find(f.value)?.groupValues?.get(1)
        LITERAL.matches(f.value) -> f.value
        else -> null
    }

    private companion object {
        val LITERAL = Regex("""^(".*"|-?\d+[LlFfDd]?|-?\d*\.\d+[FfDd]?|true|false|'.')$""")
    }
}
