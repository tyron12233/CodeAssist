package dev.ide.model

/**
 * What a facet looks like once it is persisted, separated from the codecs that produce it.
 *
 * A `FacetData` is a table name and a map of TOML-representable values — data, with no registry and no
 * filesystem behind it — and [ModifiableModule.putFacetData] takes one, so it has to be nameable wherever
 * the model is. The codec that turns a typed [Facet] into one, and the registry that resolves which codec
 * owns a table, stay with the extension registry they are built on.
 */

/** A facet persisted as the name of its `module.toml` table plus its declarative values. */
data class FacetData(
    val tomlTable: String,
    val values: Map<String, Any?>,
)

/**
 * The `module.toml` keys the model itself owns, which no facet table may use.
 *
 * Facet tables are written last, so one claiming a reserved name would overwrite the module's own
 * configuration on the next save, and the loader skips these names when it reads facets back, so the
 * overwritten data would not come back either. [FacetCodecRegistry.register] and [ModifiableModule.putFacetData]
 * both refuse them.
 */
val RESERVED_FACET_TABLES: Set<String> = setOf("version", "module", "sourceSets", "dependencies")
