package dev.ide.android.support.metadata

/**
 * The compact, hand-parsed text format for the bundled SDK metadata asset — debuggable and diffable, no
 * JSON/parser dependency, tiny on ART. One record per line, tab-separated, typed by the first token:
 *
 * ```
 * CAMETA1
 * api      <level>
 * attr     <name>  <formats csv>  <enums csv>  <flags csv>
 * styleable<name>  <attr names csv>
 * super    <simpleClass>  <simpleSuper>
 * widget   <simpleClass>  group|view
 * ```
 *
 * Round-trips [AndroidSdkMetadata] (the resolved model). Empty csv fields are the empty string.
 */
object SdkMetadataCodec {

    private const val HEADER = "CAMETA1"
    private const val TAB = "\t"

    fun write(
        apiLevel: Int,
        attrs: Map<String, AttrEntry>,
        styleables: Map<String, StyleableEntry>,
        superclass: Map<String, String>,
        widgets: List<AndroidSdkMetadata.WidgetInfo>,
    ): String = buildString {
        append(HEADER).append('\n')
        append("api").append(TAB).append(apiLevel).append('\n')
        for (a in attrs.values) {
            append("attr").append(TAB).append(a.name)
                .append(TAB).append(a.formats.joinToString(",") { it.name.lowercase() })
                .append(TAB).append(a.enumValues.joinToString(","))
                .append(TAB).append(a.flagValues.joinToString(","))
                .append('\n')
        }
        for (s in styleables.values) {
            append("styleable").append(TAB).append(s.name).append(TAB).append(s.attrs.joinToString(",")).append('\n')
        }
        for ((k, v) in superclass) append("super").append(TAB).append(k).append(TAB).append(v).append('\n')
        for (w in widgets) append("widget").append(TAB).append(w.simpleName).append(TAB)
            .append(if (w.isViewGroup) "group" else "view").append('\n')
    }

    fun read(text: String): AndroidSdkMetadata? {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != HEADER) return null
        var api = 0
        val attrs = LinkedHashMap<String, AttrEntry>()
        val styleables = LinkedHashMap<String, StyleableEntry>()
        val superclass = LinkedHashMap<String, String>()
        val widgets = ArrayList<AndroidSdkMetadata.WidgetInfo>()
        fun csv(s: String) = if (s.isEmpty()) emptyList() else s.split(",")
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val f = line.split(TAB)
            when (f[0]) {
                "api" -> api = f.getOrNull(1)?.toIntOrNull() ?: 0
                "attr" -> {
                    val name = f.getOrNull(1) ?: continue
                    val formats = csv(f.getOrElse(2) { "" }).mapNotNull(AttrFormat::parse).toSet()
                    attrs[name] = AttrEntry(name, formats, csv(f.getOrElse(3) { "" }), csv(f.getOrElse(4) { "" }))
                }
                "styleable" -> {
                    val name = f.getOrNull(1) ?: continue
                    styleables[name] = StyleableEntry(name, csv(f.getOrElse(2) { "" }))
                }
                "super" -> { val k = f.getOrNull(1); val v = f.getOrNull(2); if (k != null && v != null) superclass[k] = v }
                "widget" -> f.getOrNull(1)?.let { widgets.add(AndroidSdkMetadata.WidgetInfo(it, f.getOrNull(2) == "group")) }
            }
        }
        return AndroidSdkMetadata(api, attrs, styleables, superclass, widgets)
    }
}
