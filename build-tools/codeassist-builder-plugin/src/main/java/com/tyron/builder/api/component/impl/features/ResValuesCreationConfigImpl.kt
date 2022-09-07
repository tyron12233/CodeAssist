package com.tyron.builder.api.component.impl.features

import com.tyron.builder.api.variant.ResValue
import com.tyron.builder.gradle.internal.component.features.ResValuesCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.provider.MapProperty

class ResValuesCreationConfigImpl(
    private val dslInfo: AndroidResourcesDslInfo,
    private val internalServices: VariantServices,
): ResValuesCreationConfig {
    override val resValues: MapProperty<ResValue.Key, ResValue> by lazy {
        internalServices.mapPropertyOf(
            ResValue.Key::class.java,
            ResValue::class.java,
            dslInfo.getResValues()
        )
    }
}
