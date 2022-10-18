package com.tyron.builder.api.component.impl.features

import com.tyron.builder.api.variant.BuildConfigField
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.ConsumableComponentDslInfo
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.compiling.BuildConfigType
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import java.io.Serializable

class BuildConfigCreationConfigImpl(
    private val component: ConsumableCreationConfig,
    private val dslInfo: ConsumableComponentDslInfo,
    private val internalServices: VariantServices
): BuildConfigCreationConfig {

    override val buildConfigFields: MapProperty<String, BuildConfigField<out Serializable>> by lazy {
        internalServices.mapPropertyOf(
            String::class.java,
            BuildConfigField::class.java,
            dslInfo.getBuildConfigFields()
        )
    }
    override val dslBuildConfigFields: Map<String, BuildConfigField<out Serializable>>
        get() = dslInfo.getBuildConfigFields()

    override val compiledBuildConfig: FileCollection
        get() {
            val isBuildConfigJar = buildConfigType == BuildConfigType.JAR
            // BuildConfig JAR is not required to be added as a classpath for ANDROID_TEST and UNIT_TEST
            // variants as the tests will use JAR from GradleTestProject which doesn't use testedConfig.
            return if (isBuildConfigJar && component !is TestComponentCreationConfig) {
                internalServices.fileCollection(
                    component.artifacts.get(
                        InternalArtifactType.COMPILE_BUILD_CONFIG_JAR
                    )
                )
            } else {
                internalServices.fileCollection()
            }
        }
    override val buildConfigType: BuildConfigType
        get() = if (internalServices.projectOptions[BooleanOption.ENABLE_BUILD_CONFIG_AS_BYTECODE]
            // TODO(b/224758957): This is wrong we need to check the final build config fields from
            //  the variant API
            && dslBuildConfigFields.none()
        ) {
            BuildConfigType.JAR
        } else {
            BuildConfigType.JAVA_SOURCE
        }
}
