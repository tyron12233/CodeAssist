package dev.ide.model.impl

/**
 * Compatibility aliases. [dev.ide.model.FacetCodecRegistry] and [dev.ide.model.FacetData] moved into
 * `project-model-api` so an externally-packaged plugin can reach them: `project-model-impl` is not a
 * published artifact, so a registry that lived here was host-only by construction.
 */
@Deprecated(
    "Moved to project-model-api",
    ReplaceWith("FacetCodecRegistry", "dev.ide.model.FacetCodecRegistry"),
)
typealias FacetCodecRegistry = dev.ide.model.FacetCodecRegistry

@Deprecated("Moved to project-model-api", ReplaceWith("FacetData", "dev.ide.model.FacetData"))
typealias FacetData = dev.ide.model.FacetData
