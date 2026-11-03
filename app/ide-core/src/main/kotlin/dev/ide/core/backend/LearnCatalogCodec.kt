package dev.ide.core.backend

import dev.ide.ui.backend.UiContentBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The lesson catalog as JSON: what `exportLearnCatalog` writes, what the server stores in `learn_catalog`,
 * and what [LearnCatalogStore] reads back. The shape mirrors [LearnCatalogDef] field for field, so an
 * encode followed by a decode gives back an equal catalog (LearnCatalogCodecTest holds that).
 *
 * **Compatibility.** A catalog is read by every app version that is still installed, so the decoder is
 * built to degrade rather than fail:
 * - Unknown fields are ignored, so a newer catalog can add optional data freely.
 * - Each lesson carries a `format`. A lesson whose format is above [FORMAT] is one this build cannot show
 *   correctly, and it is dropped. So is a lesson that names a step or block type this build does not know.
 *   Only that lesson goes; its track and the rest of the catalog survive, and a track left with no lessons
 *   is dropped.
 * - The envelope's own `format` is the floor for reading the file at all. It only goes up if the top-level
 *   structure changes, which should be never.
 *
 * Exercise checks travel inside the catalog. That is no weaker than today: the bundled checks ship inside
 * the APK, and a Learn exercise is practice, not an exam.
 */
internal object LearnCatalogCodec {

    /**
     * The highest lesson format this build understands. Raise it, and make [lessonFormat] return the new
     * value for lessons that use the new feature, whenever a lesson gains something an older build would
     * mishandle rather than ignore (a new step type, a check whose absence would pass everything).
     */
    const val FORMAT: Int = 2

    /** The envelope format. Readers refuse a file whose envelope is newer than this. */
    const val ENVELOPE_FORMAT: Int = 1

    private val json = Json { prettyPrint = false }

    fun encode(catalog: LearnCatalogDef): String = json.encodeToString(JsonElement.serializer(), encodeTree(catalog))

    fun encodeTree(catalog: LearnCatalogDef): JsonObject = buildJsonObject {
        put("format", ENVELOPE_FORMAT)
        put("version", catalog.version)
        put("paths", buildJsonArray { catalog.paths.forEach { add(path(it)) } })
        put("tracks", buildJsonArray { catalog.tracks.forEach { add(track(it)) } })
    }

    /**
     * Read a catalog. Throws [IllegalArgumentException] when the text is not a catalog at all, or its
     * envelope is newer than this build reads; individual lessons that cannot be shown are dropped instead
     * (see the class comment). [dropped] receives the id of every lesson left out, for logging.
     */
    fun decode(text: String, dropped: (lessonId: String, reason: String) -> Unit = { _, _ -> }): LearnCatalogDef {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not a lesson catalog: ${it.message}") }
        val envelope = root.int("format") ?: 1
        require(envelope <= ENVELOPE_FORMAT) { "Catalog envelope format $envelope is newer than $ENVELOPE_FORMAT" }
        val version = root.long("version") ?: throw IllegalArgumentException("Catalog has no version")
        val tracks = root.array("tracks").mapNotNull { el ->
            runCatching { readTrack(el.jsonObject, dropped) }.getOrNull()
        }.filter { it.lessons.isNotEmpty() }
        val paths = root.array("paths").mapNotNull { el -> runCatching { readPath(el.jsonObject) }.getOrNull() }
        return LearnCatalogDef(version, tracks, paths)
    }

    /**
     * The format a lesson needs. 2: a hands-on CodeAssist step (workspace seed files, IDE conditions). A
     * format-1 build would show it as a code exercise whose check has nothing in it, so it must skip it.
     */
    fun lessonFormat(lesson: LearnLessonDef): Int =
        if (lesson.steps.any { it is LearnStepDef.Interactive && (it.check.ide.isNotEmpty() || it.files.isNotEmpty()) }) 2 else 1

    // ---- encode ----

    private fun path(p: LearnPathDef) = buildJsonObject {
        put("id", p.id)
        put("title", p.title)
        put("subtitle", p.subtitle)
        put("iconId", p.iconId)
        p.accentColor?.let { put("accentColor", it) }
        put("trackIds", strings(p.trackIds))
    }

    private fun track(t: LearnTrackDef) = buildJsonObject {
        put("id", t.id)
        put("title", t.title)
        put("subtitle", t.subtitle)
        put("iconId", t.iconId)
        t.accentColor?.let { put("accentColor", it) }
        put("language", t.language)
        put("category", t.category)
        put("level", t.level)
        put("prerequisites", strings(t.prerequisites))
        put("lessons", buildJsonArray { t.lessons.forEach { add(lesson(it)) } })
    }

    private fun lesson(l: LearnLessonDef) = buildJsonObject {
        put("id", l.id)
        put("format", lessonFormat(l))
        put("title", l.title)
        put("summary", l.summary)
        put("iconId", l.iconId)
        put("estMinutes", l.estMinutes)
        put("kind", l.kind)
        put("steps", buildJsonArray { l.steps.forEach { add(step(it)) } })
    }

    private fun step(s: LearnStepDef): JsonObject = when (s) {
        is LearnStepDef.Concept -> buildJsonObject {
            put("type", "concept")
            put("id", s.id)
            put("title", s.title)
            put("blocks", blocks(s.blocks))
        }
        is LearnStepDef.Interactive -> buildJsonObject {
            put("type", "interactive")
            put("id", s.id)
            put("title", s.title)
            put("blocks", blocks(s.blocks))
            put("starterCode", s.starterCode)
            put("language", s.language)
            put("hints", strings(s.hints))
            put("solution", s.solution)
            put("check", check(s.check))
            if (s.files.isNotEmpty()) put("files", JsonObject(s.files.mapValues { JsonPrimitive(it.value) }))
            s.openFile?.let { put("openFile", it) }
        }
        is LearnStepDef.Quiz -> buildJsonObject {
            put("type", "quiz")
            put("id", s.id)
            put("title", s.title)
            put("prompt", s.prompt)
            put("options", strings(s.options))
            put("correctIndex", s.correctIndex)
            put("explanation", s.explanation)
        }
    }

    private fun blocks(blocks: List<UiContentBlock>) = buildJsonArray {
        blocks.forEach { b ->
            add(
                when (b) {
                    is UiContentBlock.Text -> buildJsonObject { put("type", "text"); put("md", b.md) }
                    is UiContentBlock.Code -> buildJsonObject {
                        put("type", "code"); put("code", b.code); put("language", b.language)
                    }
                    is UiContentBlock.Callout -> buildJsonObject {
                        put("type", "callout"); put("kind", b.kind); put("text", b.text)
                    }
                    is UiContentBlock.LayoutPreview -> buildJsonObject {
                        put("type", "layoutPreview"); put("xml", b.xml)
                        put("interactive", b.interactive); put("caption", b.caption)
                    }
                    is UiContentBlock.ComposePreview -> buildJsonObject {
                        put("type", "composePreview"); put("code", b.code)
                        put("interactive", b.interactive); put("caption", b.caption)
                    }
                },
            )
        }
    }

    private fun check(c: ExerciseCheck) = buildJsonObject {
        c.expectedOutput?.let { put("expectedOutput", it) }
        put("mustContain", strings(c.mustContain))
        put("caseSensitive", c.caseSensitive)
        put("requireSource", strings(c.requireSource))
        put(
            "cases",
            buildJsonArray {
                c.cases.forEach { k ->
                    add(
                        buildJsonObject {
                            put("stdin", k.stdin)
                            k.expectedOutput?.let { put("expectedOutput", it) }
                            put("mustContain", strings(k.mustContain))
                        },
                    )
                }
            },
        )
        if (c.ide.isNotEmpty()) put("ide", buildJsonArray { c.ide.forEach { add(ideCondition(it)) } })
        c.layout?.let { l ->
            put(
                "layout",
                buildJsonObject {
                    put("cleanRender", l.cleanRender)
                    put(
                        "views",
                        buildJsonArray {
                            l.views.forEach { v ->
                                add(
                                    buildJsonObject {
                                        put("tag", v.tag)
                                        put("label", v.label)
                                        v.id?.let { put("id", it) }
                                        v.parent?.let { put("parent", it) }
                                        put("attrs", JsonObject(v.attrs.mapValues { JsonPrimitive(it.value) }))
                                        put("count", v.count)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun strings(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })

    private fun ideCondition(c: IdeCondition) = buildJsonObject {
        put("label", c.label)
        when (c) {
            is IdeCondition.FileContains -> { put("type", "fileContains"); put("path", c.path); put("text", c.text) }
            is IdeCondition.FileLacks -> { put("type", "fileLacks"); put("path", c.path); put("text", c.text) }
            is IdeCondition.Compiles -> put("type", "compiles")
            is IdeCondition.RunOutput -> { put("type", "runOutput"); put("text", c.text) }
            is IdeCondition.Dependency -> { put("type", "dependency"); put("coordinate", c.coordinate) }
            is IdeCondition.Keystore -> put("type", "keystore")
            is IdeCondition.ApkBuilt -> put("type", "apkBuilt")
            is IdeCondition.GitCommit -> put("type", "gitCommit")
        }
    }

    private fun readIdeCondition(o: JsonObject): IdeCondition {
        val label = o.req("label")
        return when (val type = o.str("type")) {
            "fileContains" -> IdeCondition.FileContains(o.req("path"), o.req("text"), label)
            "fileLacks" -> IdeCondition.FileLacks(o.req("path"), o.req("text"), label)
            "compiles" -> IdeCondition.Compiles(label)
            "runOutput" -> IdeCondition.RunOutput(o.req("text"), label)
            "dependency" -> IdeCondition.Dependency(o.req("coordinate"), label)
            "keystore" -> IdeCondition.Keystore(label)
            "apkBuilt" -> IdeCondition.ApkBuilt(label)
            "gitCommit" -> IdeCondition.GitCommit(label)
            // A condition this build cannot evaluate would pass or fail for the wrong reason: drop the lesson.
            else -> throw IllegalArgumentException("unknown IDE condition $type")
        }
    }

    // ---- decode ----

    private fun readPath(o: JsonObject) = LearnPathDef(
        id = o.req("id"),
        title = o.req("title"),
        subtitle = o.str("subtitle").orEmpty(),
        iconId = o.str("iconId") ?: "school",
        accentColor = o.long("accentColor"),
        trackIds = o.strings("trackIds"),
    )

    private fun readTrack(o: JsonObject, dropped: (String, String) -> Unit): LearnTrackDef = LearnTrackDef(
        id = o.req("id"),
        title = o.req("title"),
        subtitle = o.str("subtitle").orEmpty(),
        iconId = o.str("iconId") ?: "school",
        accentColor = o.long("accentColor"),
        language = o.str("language") ?: "none",
        category = o.str("category") ?: "General",
        lessons = o.array("lessons").mapNotNull { el ->
            val lo = el as? JsonObject ?: return@mapNotNull null
            val id = lo.str("id") ?: return@mapNotNull null
            val format = lo.int("format") ?: 1
            if (format > FORMAT) {
                dropped(id, "needs lesson format $format, this build reads $FORMAT")
                return@mapNotNull null
            }
            runCatching { readLesson(lo) }.getOrElse {
                dropped(id, it.message ?: it.javaClass.simpleName)
                null
            }
        },
        level = o.str("level").orEmpty(),
        prerequisites = o.strings("prerequisites"),
    )

    private fun readLesson(o: JsonObject) = LearnLessonDef(
        id = o.req("id"),
        title = o.req("title"),
        summary = o.str("summary").orEmpty(),
        iconId = o.str("iconId") ?: "docText",
        estMinutes = o.int("estMinutes") ?: 5,
        steps = o.array("steps").map { readStep(it.jsonObject) }.also { require(it.isNotEmpty()) { "lesson has no steps" } },
        kind = o.str("kind") ?: "lesson",
    )

    private fun readStep(o: JsonObject): LearnStepDef = when (val type = o.str("type")) {
        "concept" -> LearnStepDef.Concept(o.req("id"), o.str("title").orEmpty(), readBlocks(o.array("blocks")))
        "interactive" -> LearnStepDef.Interactive(
            id = o.req("id"),
            title = o.str("title").orEmpty(),
            blocks = readBlocks(o.array("blocks")),
            starterCode = o.req("starterCode"),
            language = o.req("language"),
            hints = o.strings("hints"),
            solution = o.req("solution"),
            check = readCheck(o["check"] as? JsonObject ?: JsonObject(emptyMap())),
            files = (o["files"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap(),
            openFile = o.str("openFile"),
        )
        "quiz" -> LearnStepDef.Quiz(
            id = o.req("id"),
            title = o.str("title").orEmpty(),
            prompt = o.req("prompt"),
            options = o.strings("options").also { require(it.size >= 2) { "quiz needs two options" } },
            correctIndex = o.int("correctIndex") ?: throw IllegalArgumentException("quiz has no answer"),
            explanation = o.str("explanation").orEmpty(),
        )
        else -> throw IllegalArgumentException("unknown step type $type")
    }

    private fun readBlocks(arr: List<JsonElement>): List<UiContentBlock> = arr.map { el ->
        val o = el.jsonObject
        when (val type = o.str("type")) {
            "text" -> UiContentBlock.Text(o.req("md"))
            "code" -> UiContentBlock.Code(o.req("code"), o.str("language") ?: "kotlin")
            "callout" -> UiContentBlock.Callout(o.str("kind") ?: "note", o.req("text"))
            "layoutPreview" -> UiContentBlock.LayoutPreview(
                o.req("xml"), o.bool("interactive") ?: false, o.str("caption").orEmpty(),
            )
            "composePreview" -> UiContentBlock.ComposePreview(
                o.req("code"), o.bool("interactive") ?: false, o.str("caption").orEmpty(),
            )
            else -> throw IllegalArgumentException("unknown block type $type")
        }
    }

    private fun readCheck(o: JsonObject) = ExerciseCheck(
        expectedOutput = o.str("expectedOutput"),
        mustContain = o.strings("mustContain"),
        caseSensitive = o.bool("caseSensitive") ?: true,
        requireSource = o.strings("requireSource"),
        cases = o.array("cases").map { el ->
            val k = el.jsonObject
            ExerciseCase(k.str("stdin").orEmpty(), k.str("expectedOutput"), k.strings("mustContain"))
        },
        ide = o.array("ide").map { readIdeCondition(it.jsonObject) },
        layout = (o["layout"] as? JsonObject)?.let { l ->
            LayoutCheck(
                views = l.array("views").map { el ->
                    val v = el.jsonObject
                    ViewRule(
                        tag = v.req("tag"),
                        label = v.str("label") ?: v.req("tag"),
                        id = v.str("id"),
                        parent = v.str("parent"),
                        attrs = (v["attrs"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }
                            ?: emptyMap(),
                        count = v.int("count") ?: 1,
                    )
                },
                cleanRender = l.bool("cleanRender") ?: true,
            )
        },
    )

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.req(key: String): String = str(key) ?: throw IllegalArgumentException("missing $key")
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
    private fun JsonObject.strings(key: String): List<String> =
        array(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
