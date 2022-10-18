package com.tyron.builder.api.component.impl.features

import com.tyron.builder.gradle.internal.component.features.ManifestPlaceholdersCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.ConsumableComponentDslInfo
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.provider.MapProperty

class ManifestPlaceholdersCreationConfigImpl(
    dslInfo: ConsumableComponentDslInfo,
    internalServices: VariantServices
): ManifestPlaceholdersCreationConfig {
    override val placeholders: MapProperty<String, String> by lazy {
        internalServices.mapPropertyOf(
            String::class.java,
            String::class.java,
            dslInfo.manifestPlaceholders
        )
    }
}