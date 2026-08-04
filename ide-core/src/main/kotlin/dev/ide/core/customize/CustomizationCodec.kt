package dev.ide.core.customize

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON (de)serialization for a [CustomizationSet] — the on-disk form AND the import/export format, so a set is
 * shared as a plain, human-readable file. Uses the kotlinx.serialization **tree** API (no `@Serializable`, no
 * compiler plugin). Deliberately tolerant: unknown keys are ignored and any missing or wrong-typed field falls
 * back to its default, so a hand-edited, partial, or older file still loads rather than throwing.
 */
object CustomizationCodec {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** The schema version, so a future format change can be detected and migrated rather than mis-read. */
    const val VERSION = 1

    fun encode(set: CustomizationSet): String {
        val root = buildJsonObject {
            put("version", VERSION)
            // A null symbol list is omitted entirely (distinct from an empty bar, which is written as []).
            set.symbols?.let { symbols ->
                putJsonArray("symbols") {
                    symbols.forEach { s ->
                        addJsonObject {
                            put("label", s.label)
                            put("insert", s.insert)
                            if (s.pinned) put("pinned", true)
                            s.action?.let { put("action", it) }
                        }
                    }
                }
            }
            if (set.macros.isNotEmpty()) putJsonArray("macros") {
                set.macros.forEach { m ->
                    addJsonObject {
                        put("abbreviation", m.abbreviation)
                        put("template", m.template)
                        if (m.description.isNotEmpty()) put("description", m.description)
                        if (m.languages.isNotEmpty()) putJsonArray("languages") { m.languages.forEach { add(it) } }
                        put("enabled", m.enabled)
                        if (m.builtIn) put("builtIn", true)
                        m.receiverType?.takeIf { it.isNotBlank() }?.let { put("receiverType", it) }
                        if (m.static) put("static", true)
                    }
                }
            }
            if (set.recorded.isNotEmpty()) putJsonArray("recorded") {
                set.recorded.forEach { r ->
                    addJsonObject {
                        put("name", r.name)
                        putJsonArray("ops") { r.ops.forEach { add(it) } }
                    }
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    fun decode(text: String): CustomizationSet {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return CustomizationSet.EMPTY
        // Present-but-not-an-array is treated as "not defined" (null) rather than an error.
        val symbols = (root["symbols"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val action = o["action"].str()
            // An action key needs no insert; a plain key needs one (either explicit or falling back to its label).
            val insert = o["insert"].str() ?: (if (action != null) "" else null) ?: return@mapNotNull null
            SymbolKeyDef(
                label = o["label"].str() ?: insert,
                insert = insert,
                pinned = o["pinned"].bool() ?: false,
                action = action,
            )
        }
        val macros = (root["macros"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val abbr = o["abbreviation"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MacroDef(
                abbreviation = abbr,
                template = o["template"].str() ?: "",
                description = o["description"].str() ?: "",
                languages = (o["languages"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList(),
                enabled = o["enabled"].bool() ?: true,
                builtIn = o["builtIn"].bool() ?: false,
                receiverType = o["receiverType"].str()?.takeIf { it.isNotBlank() },
                static = o["static"].bool() ?: false,
            )
        } ?: emptyList()
        val recorded = (root["recorded"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RecordedMacroDef(
                name = o["name"].str() ?: return@mapNotNull null,
                ops = (o["ops"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList(),
            )
        } ?: emptyList()
        return CustomizationSet(symbols = symbols, macros = macros, recorded = recorded)
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
