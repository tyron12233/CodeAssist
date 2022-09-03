package com.tyron.builder.gradle.internal.plugins

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.variant.AndroidComponentsExtension
import com.tyron.builder.api.variant.Variant
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo
import com.tyron.builder.gradle.api.AndroidBasePlugin
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import java.util.concurrent.atomic.AtomicBoolean

abstract class BasePlugin<
        BuildFeaturesT : BuildFeatures,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor,
        AndroidT : CommonExtension<
                BuildFeaturesT,
                BuildTypeT,
                DefaultConfigT,
                ProductFlavorT>,
        AndroidComponentsT :
        AndroidComponentsExtension<
                in AndroidT,
                in VariantBuilderT,
                in VariantT>,
        VariantBuilderT : VariantBuilder,
        VariantDslInfoT : VariantDslInfo,
        CreationConfigT : VariantCreationConfig,
        VariantT : Variant>(
    val registry: ToolingModelBuilderRegistry,
    val componentFactory: SoftwareComponentFactory,
    listenerRegistry: BuildEventsListenerRegistry
) : AndroidPluginBaseServices(listenerRegistry), Plugin<Project> {

    private val hasCreatedTasks = AtomicBoolean(false)

    override fun apply(project: Project) {
        basePluginApply(project)
        pluginSpecificApply(project)
        project.pluginManager.apply(AndroidBasePlugin::class.java)
    }

    protected abstract fun pluginSpecificApply(project: Project)

    override fun configureProject(project: Project) {
        val gradle = project.gradle


        // Apply the Java plugin
        project.plugins.apply(JavaBasePlugin::class.java)

        project.tasks
            .named("assemble")
            .configure { task ->
                task.description = "Assembles all variants of all applications and secondary packages."
            }
    }

    override fun configureExtension(project: Project) {

    }

    override fun createTasks(project: Project) {

    }

    fun createAndroidTasks(project: Project) {
        if (hasCreatedTasks.get()) {
            return
        }
        hasCreatedTasks.set(true)
    }
}