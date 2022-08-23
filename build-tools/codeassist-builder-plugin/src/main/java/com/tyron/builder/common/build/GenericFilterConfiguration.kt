package com.tyron.builder.common.build

/**
 * Generic version of the gradle-api FilterConfiguration with gradle specific types removed.
 */
data class GenericFilterConfiguration(val filterType: String, val identifier: String)