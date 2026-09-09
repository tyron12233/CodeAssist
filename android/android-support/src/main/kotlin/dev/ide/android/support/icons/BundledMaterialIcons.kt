package dev.ide.android.support.icons

/**
 * The Material Symbols subset that ships inside the app, so the Icon Manager always has something to browse
 * with no network and no project open.
 *
 * Backed by a generated tab-separated resource (see `.github/scripts/fetch_material_icons.py`). Every icon in
 * it is a single path in the same 960-unit box, so the shared viewport and viewBox origin live in the file
 * header and each row carries only the two path-data strings (outlined and filled) plus its search terms.
 * Rows are ordered by Google's popularity metadata, which is also the order the picker shows them in.
 */
class BundledMaterialIcons(
    private val resourcePath: String = DEFAULT_RESOURCE,
) : IconRepository {

    override val id: String get() = ID
    override val displayName: String get() = "Material Symbols"
    override val license: String get() = "Apache-2.0"
    override val attribution: String get() = "Google Material Symbols"
    override val requiresNetwork: Boolean get() = false

    private val data: Data by lazy(LazyThreadSafetyMode.PUBLICATION) { read(resourcePath) }

    override fun entries(): List<IconEntry> = data.entries

    override fun artwork(entry: IconEntry, variant: IconVariant): IconArtwork? {
        val row = data.rows[entry.name] ?: return null
        val pathData = (if (variant.filled) row.filled else row.outlined).ifEmpty { return null }
        val spec = singlePathVector(
            pathData = pathData,
            viewport = data.viewport,
            originX = data.originX,
            originY = data.originY,
            sizeDp = DEFAULT_SIZE_DP,
            color = DEFAULT_COLOR,
        )
        // Only OUTLINED ships in the bundle; asking for another family is a mismatch worth reporting rather
        // than silently drawing the wrong style.
        val warnings = if (variant.style == IconStyle.OUTLINED) emptyList()
        else listOf("The bundled set only ships the outlined family; showing outlined instead of ${variant.style.name.lowercase()}")
        return IconArtwork(spec, warnings)
    }

    private class Row(val outlined: String, val filled: String)

    private class Data(
        val entries: List<IconEntry>,
        val rows: Map<String, Row>,
        val viewport: Float,
        val originX: Float,
        val originY: Float,
    ) {
        companion object {
            val EMPTY = Data(emptyList(), emptyMap(), 24f, 0f, 0f)
        }
    }

    private fun read(path: String): Data {
        val text = javaClass.getResourceAsStream(path)?.use { it.readBytes().decodeToString() } ?: return Data.EMPTY
        var viewport = 24f
        var originX = 0f
        var originY = 0f
        val entries = ArrayList<IconEntry>()
        val rows = LinkedHashMap<String, Row>()

        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith('#')) continue
            if (line.startsWith('!')) {
                val parts = line.substring(1).split(' ').filter { it.isNotBlank() }
                when (parts.firstOrNull()) {
                    "viewport" -> parts.getOrNull(1)?.toFloatOrNull()?.let { viewport = it }
                    "offset" -> {
                        parts.getOrNull(1)?.toFloatOrNull()?.let { originX = it }
                        parts.getOrNull(2)?.toFloatOrNull()?.let { originY = it }
                    }
                }
                continue
            }
            val cols = line.split('\t')
            if (cols.size < 5) continue
            val name = cols[0]
            if (name.isEmpty() || name in rows) continue
            rows[name] = Row(outlined = cols[3], filled = cols[4])
            entries += IconEntry(
                repositoryId = ID,
                name = name,
                displayName = humanize(name),
                keywords = cols[2].split(',').filter { it.isNotBlank() },
                category = cols[1].ifBlank { null },
                styles = setOf(IconStyle.OUTLINED),
                supportsFill = cols[4].isNotEmpty(),
            )
        }
        return Data(entries, rows, viewport, originX, originY)
    }

    companion object {
        const val ID = "material-symbols-bundled"

        /** Resource path, relative to this class's package. */
        const val DEFAULT_RESOURCE = "material-symbols.tsv"

        private const val DEFAULT_SIZE_DP = 24f
        private const val DEFAULT_COLOR = 0xFF000000L

        /** `add_circle_outline` reads as `Add circle outline` in the picker. */
        internal fun humanize(name: String): String {
            val words = name.split('_', '-').filter { it.isNotEmpty() }
            if (words.isEmpty()) return name
            return words.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
        }
    }
}
