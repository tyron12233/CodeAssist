package dev.ide.core.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The catalog JSON is read by every installed app version, so it has two jobs: carry the bundled catalog
 * exactly (round trip), and degrade one lesson at a time when a newer catalog uses something an older app
 * does not know.
 */
class LearnCatalogCodecTest {

    @Test
    fun theBundledCatalogSurvivesARoundTrip() {
        val catalog = LearnContent.catalog
        val back = LearnCatalogCodec.decode(LearnCatalogCodec.encode(catalog))
        assertEquals(catalog.version, back.version)
        assertEquals(catalog.paths, back.paths)
        assertEquals(catalog.tracks.map { it.id }, back.tracks.map { it.id })
        for ((a, b) in catalog.tracks.zip(back.tracks)) assertEquals(a, b, "track ${a.id} changed in the round trip")
    }

    @Test
    fun aLessonFromAFutureFormatIsDroppedAndTheRestSurvive() {
        val tree = LearnCatalogCodec.encodeTree(LearnContent.catalog)
        val firstTrack = tree.array("tracks")[0].jsonObject
        val firstLessonId = firstTrack.array("lessons")[0].jsonObject.str("id")
        val edited = tree.editLesson(0, 0) { it + ("format" to JsonPrimitive(LearnCatalogCodec.FORMAT + 1)) }
        val dropped = ArrayList<String>()
        val back = LearnCatalogCodec.decode(edited.toString()) { id, _ -> dropped += id }
        assertEquals(listOf(firstLessonId), dropped)
        val track = back.tracks.first { it.id == firstTrack.str("id") }
        assertEquals(LearnContent.tracks[0].lessons.size - 1, track.lessons.size)
        assertEquals(LearnContent.tracks.size, back.tracks.size)
    }

    @Test
    fun aLessonWithAnUnknownBlockTypeIsDropped() {
        val tree = LearnCatalogCodec.encodeTree(LearnContent.catalog)
        val edited = tree.editLesson(0, 1) { lesson ->
            val steps = lesson.array("steps").toMutableList()
            val step = steps[0].jsonObject
            val blocks = step.array("blocks") + JsonObject(mapOf("type" to JsonPrimitive("hologram")))
            steps[0] = JsonObject(step + ("blocks" to JsonArray(blocks)))
            lesson + ("steps" to JsonArray(steps))
        }
        val dropped = ArrayList<String>()
        LearnCatalogCodec.decode(edited.toString()) { id, _ -> dropped += id }
        assertEquals(listOf(LearnContent.tracks[0].lessons[1].id), dropped)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val tree = LearnCatalogCodec.encodeTree(LearnContent.catalog)
        val edited = JsonObject(tree + ("somethingNew" to JsonPrimitive(true)))
            .editLesson(0, 0) { it + ("sparkles" to JsonPrimitive(3)) }
        val back = LearnCatalogCodec.decode(edited.toString())
        assertEquals(LearnContent.tracks[0].lessons[0], back.tracks[0].lessons[0])
    }

    @Test
    fun aNewerEnvelopeIsRefused() {
        val tree = LearnCatalogCodec.encodeTree(LearnContent.catalog)
        val newer = JsonObject(tree + ("format" to JsonPrimitive(LearnCatalogCodec.ENVELOPE_FORMAT + 1)))
        assertFailsWith<IllegalArgumentException> { LearnCatalogCodec.decode(newer.toString()) }
        assertFailsWith<IllegalArgumentException> { LearnCatalogCodec.decode("not json") }
    }

    @Test
    fun multiCaseAndLayoutChecksRoundTrip() {
        val lesson = LearnLessonDef(
            id = "x", title = "X", summary = "", kind = "capstone",
            steps = listOf(
                LearnStepDef.Interactive(
                    id = "s1", title = "S", blocks = emptyList(), starterCode = "a", language = "kotlin",
                    solution = "b",
                    check = ExerciseCheck(cases = listOf(ExerciseCase("1\n2\n", "3"), ExerciseCase("", mustContain = listOf("ok")))),
                ),
                LearnStepDef.Interactive(
                    id = "s2", title = "L", blocks = emptyList(), starterCode = "<a/>", language = "xml-layout",
                    solution = "<b/>",
                    check = ExerciseCheck(
                        layout = LayoutCheck(
                            listOf(ViewRule("Button", "a button", id = "go", parent = "LinearLayout", attrs = mapOf("android:text" to "Go"), count = 2)),
                            cleanRender = false,
                        ),
                    ),
                ),
            ),
        )
        val catalog = LearnCatalogDef(7, listOf(LearnContent.tracks[0].copy(lessons = listOf(lesson))))
        assertEquals(catalog, LearnCatalogCodec.decode(LearnCatalogCodec.encode(catalog)))
    }

    @Test
    fun theExportIsCompactEnoughToDownload() {
        val size = LearnCatalogCodec.encode(LearnContent.catalog).length
        assertTrue(size < 2 * 1024 * 1024, "catalog JSON is $size bytes")
        Json.parseToJsonElement(LearnCatalogCodec.encode(LearnContent.catalog))
    }

    private fun JsonObject.array(key: String) = (this[key] as JsonArray)
    private fun JsonObject.str(key: String) = (this[key] as JsonPrimitive).content

    private fun JsonObject.editLesson(track: Int, lesson: Int, edit: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        val tracks = array("tracks").toMutableList()
        val t = tracks[track].jsonObject
        val lessons = t.array("lessons").toMutableList()
        lessons[lesson] = JsonObject(edit(lessons[lesson].jsonObject))
        tracks[track] = JsonObject(t + ("lessons" to JsonArray(lessons)))
        return JsonObject(this + ("tracks" to JsonArray(tracks)))
    }
}
