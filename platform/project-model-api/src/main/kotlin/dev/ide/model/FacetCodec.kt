package dev.ide.model

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.platform.log.Log
import java.util.concurrent.ConcurrentHashMap

private val log = Log.logger("ide.model")

/**
 * Persistence adapter for a facet type. The core cannot serialize a [Facet] generically (the api only
 * exposes its [FacetKey]), so each facet-bearing plugin contributes a codec that maps its facet to and
 * from a declarative `module.toml` table (e.g. `[android]`, `[java]`).
 *
 * A codec is **required**, not optional: [ModifiableModule.putFacet] refuses a facet whose key has none, and
 * [FacetContainer.get] answers null for one. A facet type and its codec are two halves of one contribution,
 * and a plugin registers both or neither. What does survive without a codec is a `module.toml` table nobody
 * claims: it is carried through a load/save cycle untouched, so a project edited with a plugin disabled does
 * not lose that plugin's configuration.
 *
 * Codec values must use only TOML-representable types (String, Boolean, Int, Long, Double, and lists or
 * string-keyed maps of those), so that `encode` and a load-from-disk produce structurally equal values. An
 * unset field is an absent key rather than a null, which has no TOML representation. Both are checked where
 * a facet is staged ([ModifiableModule.putFacet], [ModifiableModule.putFacetData]) rather than at the next
 * save, so a codec emitting something unwritable names its own table.
 */
interface FacetCodec<T : Facet> {
    val key: FacetKey<T>

    /**
     * The `module.toml` table this facet occupies. It is the on-disk identity of the facet and a flat global
     * namespace across every plugin, so prefer a name that reads as the domain (`android`, `python`) and
     * expect the last registration for a table to win. A second codec for a table already claimed is logged
     * by [FacetCodecRegistry], because the loser keeps working for its own key while every table-keyed read
     * goes to the winner, which is otherwise invisible from both sides.
     *
     * A name in [RESERVED_FACET_TABLES] is refused: those tables are the model's own.
     *
     * Claiming another plugin's table to write values into it is not the way to configure a facet whose class
     * is out of reach. [ModifiableModule.putFacetData] does that without a codec.
     */
    val tomlTable: String
    fun encode(facet: T): Map<String, Any?>
    fun decode(values: Map<String, Any?>): T
}

/**
 * Plugins contribute facet codecs here; the model persistence resolves a facet's codec against it, so a
 * facet-bearing plugin's `module.toml` codec is a registration like every other capability.
 *
 * Declared alongside [Facet] rather than with the registry that reads it: contributing a codec is something
 * a plugin does, and a plugin should not have to depend on the model's implementation to do it.
 */
val FACET_CODEC_EP = ExtensionPoint<FacetCodec<*>>("platform.facetCodec")

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

/**
 * An [ExtensionRegistry]-backed view over [FACET_CODEC_EP]: the host builds one over its application registry
 * and a facet-bearing plugin contributes its codec through it (or directly on the EP). A registration wins over
 * earlier ones for the same key/table (last-write-wins). The no-arg constructor makes a standalone registry
 * over its own private EP registry, for tests or one-off persistence with no host.
 *
 * Contributing through a plugin's `PluginRegistration` is equivalent and is what a plugin should do, because the
 * registration is then attributed to it and removed when it unloads:
 *
 * ```
 * override fun register(reg: PluginRegistration) {
 *     reg.register(FACET_CODEC_EP, PythonFacetCodec)
 * }
 * ```
 *
 * The codec list is read through the registry on every lookup rather than captured, so a plugin that loads
 * after this object was built is still seen.
 */
class FacetCodecRegistry(private val extensions: ExtensionRegistry) {
    constructor() : this(ExtensionRegistryImpl())

    private val codecs: List<FacetCodec<*>> get() = extensions.extensions(FACET_CODEC_EP)

    /** Tables already reported as contested, so a shadowed one is logged once and not on every lookup. */
    private val reportedTables = ConcurrentHashMap.newKeySet<String>()

    /**
     * Contribute [codec], attributed to [plugin] so that unloading it removes the codec with the rest of its
     * contributions. The default attributes to no plugin, which is right for a standalone registry with no
     * host (a test, a one-off persistence run) and wrong for a plugin: nothing removes an unattributed
     * registration, so it outlives the plugin that made it. Prefer `reg.register(FACET_CODEC_EP, codec)`,
     * which cannot get this wrong.
     */
    fun register(codec: FacetCodec<*>, plugin: PluginId = DEFAULT_PLUGIN): FacetCodecRegistry {
        require(codec.tomlTable !in RESERVED_FACET_TABLES) {
            "facet codec ${codec::class.java.name} claims '${codec.tomlTable}', " +
                "which is a module.toml table the model owns (${RESERVED_FACET_TABLES.joinToString()})"
        }
        extensions.register(FACET_CODEC_EP, codec, plugin)
        if (codecs.count { it.tomlTable == codec.tomlTable } > 1) reportShadowed(codec.tomlTable)
        return this
    }

    /**
     * The codec for [key]. [FacetKey] has reference identity, so this matches the key *instance* the codec
     * declares: a facet and its codec must name the same `val`, and two keys sharing an id are still two keys.
     */
    fun codecFor(key: FacetKey<*>): FacetCodec<*>? = codecs.lastOrNull { it.key == key }

    fun codecForTable(table: String): FacetCodec<*>? {
        var last: FacetCodec<*>? = null
        var claimants = 0
        for (codec in codecs) {
            if (codec.tomlTable == table) {
                last = codec
                claimants++
            }
        }
        if (claimants > 1) reportShadowed(table)
        return last
    }

    /**
     * Report a table two or more codecs claim.
     *
     * Table names are one flat namespace across every plugin and the last registration wins, which is
     * resolvable but not observable: the codec that loses keeps working for [codecFor] (matched by key
     * identity) while every table-keyed read goes to the winner, so a plugin can take over persistence for
     * another plugin's facet without either of them showing a symptom. Naming both codecs is the only signal
     * available, since the extension registry does not carry the contributing plugin id through a read.
     *
     * Once per table per registry: this is called from a lookup, and a contested table is looked up as often
     * as any other.
     */
    private fun reportShadowed(table: String) {
        if (!reportedTables.add(table)) return
        val claimants = codecs.filter { it.tomlTable == table }.map { it::class.java.name }
        log.warn(
            "module.toml table '$table' is claimed by ${claimants.size} facet codecs " +
                "(${claimants.joinToString()}); '${claimants.last()}' wins every lookup by table and the " +
                "rest are ignored",
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun encode(facet: Facet): FacetData? {
        val codec = (codecFor(facet.key) ?: return null) as FacetCodec<Facet>
        return FacetData(codec.tomlTable, codec.encode(facet))
    }

    fun decode(data: FacetData): Facet? = codecForTable(data.tomlTable)?.decode(data.values)

    private companion object {
        val DEFAULT_PLUGIN = PluginId("facet-codec")
    }
}
