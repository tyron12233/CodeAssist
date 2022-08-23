package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.FilterConfiguration
import org.gradle.api.tasks.Input
import java.io.Serializable

data class FilterConfigurationImpl(
        @get:Input
        override val filterType: FilterConfiguration.FilterType,
        @get:Input
        override val identifier: String,
) : FilterConfiguration, Serializable {

    override fun toString(): String =
            "FilterConfiguration(filterType=$filterType, identifier=$identifier)"
}