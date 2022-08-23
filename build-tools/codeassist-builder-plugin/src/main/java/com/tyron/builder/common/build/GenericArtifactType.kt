package com.tyron.builder.common.build
/**
 * Generic version of the public gradle-api ArtifactType with all gradle specific types removed.
 */
data class GenericArtifactType(val type: String, val kind: String)