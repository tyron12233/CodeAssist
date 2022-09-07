package com.tyron.builder.gradle.internal.plugins

import com.android.AndroidProjectTypes
import com.tyron.builder.api.dsl.*
import com.tyron.builder.api.variant.AndroidComponentsExtension
import com.tyron.builder.api.variant.Variant
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import javax.inject.Inject

/** Gradle plugin class for 'application' projects.  */
abstract class AbstractAppPlugin<
        BuildFeaturesT: BuildFeatures,
        BuildTypeT: BuildType,
        DefaultConfigT: DefaultConfig,
        ProductFlavorT: ProductFlavor,
        AndroidT: CommonExtension<
                BuildFeaturesT,
                BuildTypeT,
                DefaultConfigT,
                ProductFlavorT>,
        AndroidComponentsT : AndroidComponentsExtension<
                in AndroidT,
                in VariantBuilderT,
                in VariantT>,
        VariantBuilderT : VariantBuilder,
        VariantDslInfoT: VariantDslInfo,
        CreationConfigT: VariantCreationConfig,
        VariantT : Variant>
@Inject constructor(
    registry: ToolingModelBuilderRegistry?,
    componentFactory: SoftwareComponentFactory?,
    listenerRegistry: BuildEventsListenerRegistry?
) : BasePlugin<BuildFeaturesT, BuildTypeT, DefaultConfigT, ProductFlavorT, AndroidT, AndroidComponentsT, VariantBuilderT, VariantDslInfoT, CreationConfigT, VariantT>(
    registry!!,
    componentFactory!!,
    listenerRegistry!!
) {

    override fun getProjectType(): Int {
        return AndroidProjectTypes.PROJECT_TYPE_APP
    }

}