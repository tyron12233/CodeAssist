package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seasonal tracks: the New Year card track is listed from Nov 23 through Jan 6 (local date), a window that
 * wraps the new year, and nowhere else. The filter lives in [LearnBackend], so the UI only ever sees the
 * tracks that are in season.
 */
class LearnSeasonTest {

    private val newYear = LearnContent.tracks.first { it.id == "new-year-card" }

    @Test
    fun theWinterWindowWrapsTheNewYear() {
        val w = assertNotNull(newYear.visibleIn)
        // Inside, on both sides of the new year, including both ends.
        for (d in listOf("2026-11-23", "2026-11-28", "2026-12-01", "2026-12-25", "2026-12-31", "2027-01-01", "2027-01-06")) {
            assertTrue(w.contains(LocalDate.parse(d)), "$d should be in season")
        }
        // Outside, just past each end and mid-year.
        for (d in listOf("2026-11-22", "2027-01-07", "2026-07-15", "2028-02-29", "2026-11-01")) {
            assertFalse(w.contains(LocalDate.parse(d)), "$d should be out of season")
        }
    }

    @Test
    fun aWindowThatDoesNotWrapIsAPlainRange() {
        val w = SeasonWindow(3, 10, 4, 2)
        assertTrue(w.contains(LocalDate.parse("2026-03-10")))
        assertTrue(w.contains(LocalDate.parse("2026-04-02")))
        assertFalse(w.contains(LocalDate.parse("2026-03-09")))
        assertFalse(w.contains(LocalDate.parse("2026-04-03")))
        assertFalse(w.contains(LocalDate.parse("2026-12-25")))
    }

    @Test
    fun theCatalogListsTheTrackOnlyInSeason() {
        runBlocking {
            for ((date, expected) in listOf(
                "2026-11-22" to false,
                "2026-11-23" to true,
                "2026-12-24" to true,
                "2027-01-06" to true,
                "2027-01-07" to false,
                "2027-06-01" to false,
            )) {
                val learn = LearnBackend(IdeServicesBackend(), today = { LocalDate.parse(date) })
                val ids = learn.catalog().tracks.map { it.id }
                assertEquals(expected, "new-year-card" in ids, "on $date the track should be ${if (expected) "listed" else "hidden"}")
                // Every other track is unaffected by the season.
                assertEquals(LearnContent.tracks.count { it.visibleIn == null }, ids.count { it != "new-year-card" })
            }
        }
    }

    @Test
    fun outOfSeasonTheLessonStillLoadsButIsNotOfferedToResume() {
        runBlocking {
            withTempDir("learn-season") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val backend = IdeServicesBackend(manager = manager)
                    var date = LocalDate.parse("2026-12-20")
                    val learn = LearnBackend(backend, today = { date })
                    learn.recordVisit("ny-lights", 1)
                    assertEquals("new-year-card", learn.resume()?.trackId, "in season the Continue card offers it")

                    date = LocalDate.parse("2027-01-07")
                    assertNull(learn.resume(), "out of season the track is not listed, so Continue must not lead to it")
                    assertNotNull(learn.lesson("ny-lights"), "a stored link still opens the lesson")
                } finally {
                    manager.dispose()
                }
            }
        }
    }

    @Test
    fun theGreetingExerciseRejectsAnAnswerThatSkipsTheFunction() {
        val step = newYear.lessons.flatMap { it.steps }.filterIsInstance<LearnStepDef.Interactive>().single()
        // Right output, but the greeting is built inline in main, so cardGreeting is never used.
        val inlined = """
            fun cardGreeting(name: String): String = ""

            fun main() {
                val who = readln().trim()
                println(if (who.isEmpty()) "Happy New Year, friend!" else "Happy New Year, " + who + "!")
            }
        """.trimIndent()
        assertEquals(listOf("println(cardGreeting("), missingConstructs(inlined, step.check.requireSource))
        // A differently spaced but faithful answer passes the source check.
        val spaced = "fun  cardGreeting( name : String ) : String { return name }\nfun main() { println( cardGreeting( readln() ) ) }"
        assertTrue(missingConstructs(spaced, step.check.requireSource).isEmpty())
    }

    /** House style for user-facing text: no em dashes. */
    @Test
    fun theTrackTextHasNoEmDashes() {
        val bad = trackTexts().filter { '\u2014' in it }
        assertTrue(bad.isEmpty(), "em dashes in: $bad")
    }

    /**
     * The season is holiday-neutral: a winter building to the New Year, for everyone. No Christmas wording in
     * the prose or in the code samples.
     */
    @Test
    fun theTrackIsHolidayNeutral() {
        val words = Regex("""\b(christmas|xmas|merry|santa|ornaments?|gifts?|presents?|trees?|church|wine|champagne)\b""", RegexOption.IGNORE_CASE)
        val bad = trackTexts().mapNotNull { t -> words.find(t)?.let { "${it.value} in: ${t.take(120)}" } }
        assertTrue(bad.isEmpty(), "holiday-specific wording: $bad")
        assertTrue(newYear.lessons.all { it.id.startsWith("ny-") })
    }

    /** Every piece of text the track shows: titles, prose, quizzes, and the code samples and exercise files. */
    private fun trackTexts(): List<String> {
        val texts = ArrayList<String>()
        texts += listOf(newYear.title, newYear.subtitle, newYear.category)
        for (l in newYear.lessons) {
            texts += listOf(l.title, l.summary)
            for (s in l.steps) {
                texts += s.title
                when (s) {
                    is LearnStepDef.Concept -> s.blocks.forEach { texts += it.toString() }
                    is LearnStepDef.Interactive -> {
                        s.blocks.forEach { texts += it.toString() }
                        texts += s.hints
                        texts += s.starterCode
                        texts += s.solution
                    }
                    is LearnStepDef.Quiz -> {
                        texts += s.prompt
                        texts += s.options
                        texts += s.explanation
                    }
                }
            }
        }
        return texts
    }

    /** The real checker (compile + run each case): the solution passes, the starter does not. */
    @Test
    fun theGreetingExerciseSolutionPassesAndItsStarterFails() {
        runBlocking {
            withTempDir("learn-season-run") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val learn = IdeServicesBackend(manager = manager).learn
                    val lesson = newYear.lessons.first { l -> l.steps.any { it is LearnStepDef.Interactive } }
                    val step = lesson.steps.filterIsInstance<LearnStepDef.Interactive>().single()
                    val good = learn.check(lesson.id, step.id, step.solution.trimIndent())
                    assertTrue(good.passed, "${good.message} ${good.diagnostics}\n${good.output}")
                    val starter = learn.check(lesson.id, step.id, step.starterCode.trimIndent())
                    assertFalse(starter.passed, "the starter must not pass: ${starter.message}")
                } finally {
                    manager.dispose()
                }
            }
        }
    }
}
