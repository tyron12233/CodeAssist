package dev.ide.android.support

import dev.ide.android.support.viewbinding.BindingClass
import dev.ide.android.support.viewbinding.LayoutBindingModel
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticParam
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Contributes the light ViewBinding classes for an Android module whose `buildFeatures { viewBinding }` is
 * on — `<namespace>.databinding.<Layout>Binding`, one per layout, with a typed field per `android:id` plus
 * `getRoot()`/`inflate`/`bind`, generated from the module's own layout XML ([LayoutBindingModel]). So
 * `ActivityMainBinding.inflate(…)` and `binding.title.setText(…)` resolve for completion/analysis/go-to-def
 * *before* a build, exactly as the real generated classes will after one (the build emits the same shape).
 *
 * Returns nothing for a non-Android module or one with viewBinding off, so it's inert until opted in.
 */
class AndroidViewBindingProvider : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val facet = context.module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        if (!facet.buildFeatures.viewBinding) return emptyList()

        val resDirs = resDirs(context.module)
        if (resDirs.isEmpty()) return emptyList()
        return LayoutBindingModel.bindingsFor(resDirs, facet.namespace).map(::syntheticClass)
    }

    /** A module's own (non-test) `res/` roots — ViewBinding generates from a module's *own* layouts. */
    private fun resDirs(module: Module): List<Path> =
        module.sourceSets.filter { it.scope != DependencyScope.TEST_IMPLEMENTATION }
            .flatMap { it.contentRoots }
            .filter { ContentRole.ANDROID_RES in it.roles }
            .map { Paths.get(it.dir.path) }

    private fun syntheticClass(b: BindingClass): SyntheticClass {
        val self = b.simpleName // factory/return type referring to the binding itself (same package)
        val fields = b.fields.map { SyntheticField(it.name, type = it.viewType, modifiers = INSTANCE_FIELD) }
        val methods = listOf(
            SyntheticMethod("getRoot", returnType = b.rootViewType),
            SyntheticMethod(
                "inflate", returnType = self, modifiers = STATIC,
                parameters = listOf(SyntheticParam("inflater", LAYOUT_INFLATER)),
            ),
            SyntheticMethod(
                "inflate", returnType = self, modifiers = STATIC,
                parameters = listOf(
                    SyntheticParam("inflater", LAYOUT_INFLATER),
                    SyntheticParam("parent", VIEW_GROUP),
                    SyntheticParam("attachToParent", "boolean"),
                ),
            ),
            SyntheticMethod(
                "bind", returnType = self, modifiers = STATIC,
                parameters = listOf(SyntheticParam("view", VIEW)),
            ),
        )
        return SyntheticClass(
            fqName = b.fqName,
            modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL),
            fields = fields,
            methods = methods,
            doc = "ViewBinding for res/layout/${b.layoutName}.xml (synthetic)",
        )
    }

    private companion object {
        const val VIEW = "android.view.View"
        const val VIEW_GROUP = "android.view.ViewGroup"
        const val LAYOUT_INFLATER = "android.view.LayoutInflater"
        // Binding fields are instance fields (public final), unlike R's static int constants.
        val INSTANCE_FIELD = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL)
        val STATIC = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC)
    }
}
