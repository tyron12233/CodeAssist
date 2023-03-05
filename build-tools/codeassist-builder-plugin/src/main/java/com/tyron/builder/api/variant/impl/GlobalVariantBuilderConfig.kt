package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.DependenciesInfo

/**
 * Global Config for VariantBuilder objects.
 *
 * This gives access to a few select objects that are needed by the VariantBuilder
 * API but are not variant specific.
 *
 * IMPORTANT: it must not give access to the whole extension as it is too dangerous. We need to
 * control that is accessible (DSL elements that are global) and what isn't (DSL
 * elements that are configurable per-variant). Giving access directly to the DSL removes this
 * safety net and reduce maintainability in the future when things become configurable per-variant.
 */

interface GlobalVariantBuilderConfig {

    val dependenciesInfo: DependenciesInfo
}