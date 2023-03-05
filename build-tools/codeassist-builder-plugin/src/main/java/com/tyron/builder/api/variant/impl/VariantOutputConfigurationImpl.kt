package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.VariantOutputConfiguration
import com.tyron.builder.api.variant.VariantOutputConfiguration.*
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.internal.utils.appendCamelCase
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import java.io.File
import java.io.Serializable
import java.util.Locale

data class VariantOutputConfigurationImpl(
    @get:Input
    val isUniversal: Boolean = false,
    @get:Nested
    override val filters: Collection<FilterConfigurationImpl> = listOf()
) : VariantOutputConfiguration, Serializable {

    @get:Input
    override val outputType: OutputType
        get() {
            if (isUniversal) return OutputType.UNIVERSAL
            return if (filters.isEmpty()) OutputType.SINGLE
            else OutputType.ONE_OF_MANY
        }
}

/**
 * Returns the [FilterConfiguration] for a particular [FilterConfiguration.FilterType] or null
 * if not such filter is configured on this variant output
 */
fun VariantOutputConfiguration.getFilter(type: FilterConfiguration.FilterType)
        : FilterConfiguration? = filters.firstOrNull { it.filterType == type }

fun VariantOutputConfiguration.baseName(component: ComponentCreationConfig): String =
        when(this.outputType) {
            OutputType.SINGLE -> component.baseName
            OutputType.UNIVERSAL -> component.paths.computeBaseNameWithSplits(
                OutputType.UNIVERSAL.name.lowercase(Locale.US)
            )
            OutputType.ONE_OF_MANY ->
                component.paths.computeBaseNameWithSplits(this.filters.getFilterName())
        }


fun VariantOutputConfiguration.dirName(): String {
    return when (this.outputType) {
        OutputType.UNIVERSAL -> outputType.name.lowercase(Locale.US)
        OutputType.SINGLE -> ""
        OutputType.ONE_OF_MANY ->
            filters.map(FilterConfiguration::identifier).joinToString(File.separator)
        else -> throw RuntimeException("Unhandled OutputType $this")
    }
}

fun VariantOutputConfiguration.fullName(component: ComponentCreationConfig): String {
    return when (this.outputType) {
        OutputType.UNIVERSAL ->
            component.paths.computeFullNameWithSplits(
                OutputType.UNIVERSAL.name.lowercase(Locale.US))
        OutputType.SINGLE ->
            component.name
        OutputType.ONE_OF_MANY -> {
            val filterName = filters.getFilterName()
            return component.paths.computeFullNameWithSplits(filterName)
        }
        else -> throw RuntimeException("Unhandled OutputType $this")
    }
}

fun Collection<FilterConfiguration>.joinToString() =
    this.joinToString { filter -> filter.identifier }

fun Collection<FilterConfiguration>.getFilterName(): String {
    val sb = StringBuilder()
    val densityFilter = firstOrNull { it.filterType == FilterConfiguration.FilterType.DENSITY }?.identifier
    if (densityFilter != null) {
        sb.append(densityFilter)
    }
    val abiFilter = firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
    if (abiFilter != null) {
        sb.appendCamelCase(abiFilter)
    }
    return sb.toString()
}