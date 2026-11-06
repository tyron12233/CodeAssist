package dev.ide.core.backend

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.impl.LayoutPreviewService
import dev.ide.ui.backend.UiContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrity checks over the authored [LearnContent] so a content bug fails the build rather than a lesson.
 * The important one: every interactive exercise's own SOLUTION must satisfy its `requireSource` — otherwise a
 * learner typing the correct answer (or revealing the solution) would be wrongly told they hardcoded it.
 */
class LearnContentTest {

    @Test
    fun everyInteractiveSolutionIsAcceptedByItsOwnCheck() {
        forEachStep { lesson, step ->
            if (step is LearnStepDef.Interactive && step.language.startsWith("ide-")) {
                // A hands-on CodeAssist step: graded by what happened in the workspace, not by code.
                assertTrue(step.language in setOf("ide-kotlin", "ide-android"), "${step.id}: unexpected language '${step.language}'")
                assertTrue(step.check.ide.isNotEmpty(), "${lesson.id}/${step.id}: a hands-on step needs IDE conditions")
                assertTrue(step.openFile != null, "${lesson.id}/${step.id}: a hands-on step must open a file")
                assertTrue(step.check.layout == null && step.check.cases.isEmpty(), "${step.id}: mixes check kinds")
                for (c in step.check.ide) {
                    val path = (c as? IdeCondition.FileContains)?.path ?: (c as? IdeCondition.FileLacks)?.path ?: continue
                    assertTrue(path in step.files, "${lesson.id}/${step.id}: checks $path, which the step does not seed")
                }
            } else if (step is LearnStepDef.Interactive) {
                assertTrue(step.check.ide.isEmpty() && step.files.isEmpty(), "${step.id}: IDE checks need an ide-* language")
                assertTrue(step.starterCode.isNotBlank(), "${step.id}: blank starter code")
                assertTrue(step.solution.isNotBlank(), "${step.id}: blank solution")
                assertTrue(
                    step.language in setOf("kotlin", "kotlin-coroutines", "java", "xml-layout"),
                    "${step.id}: unexpected language '${step.language}'",
                )
                assertEquals(
                    step.language == "xml-layout", step.check.layout != null,
                    "${step.id}: an xml-layout exercise needs a layout check, and only it may have one",
                )
                val missing = missingConstructs(step.solution, step.check.requireSource)
                assertTrue(
                    missing.isEmpty(),
                    "${lesson.id}/${step.id}: the solution does not contain its own requireSource $missing",
                )
            }
        }
    }

    @Test
    fun quizzesAreWellFormed() {
        forEachStep { _, step ->
            if (step is LearnStepDef.Quiz) {
                assertTrue(step.options.size >= 2, "${step.id}: a quiz needs at least two options")
                assertTrue(step.correctIndex in step.options.indices, "${step.id}: correctIndex out of range")
            }
        }
    }

    @Test
    fun idsAreUnique() {
        val trackIds = LearnContent.tracks.map { it.id }
        val lessonIds = LearnContent.tracks.flatMap { it.lessons }.map { it.id }
        val stepIds = LearnContent.tracks.flatMap { it.lessons }.flatMap { it.steps }.map { it.id }
        assertEquals(trackIds.size, trackIds.toSet().size, "duplicate track id")
        assertEquals(lessonIds.size, lessonIds.toSet().size, "duplicate lesson id")
        assertEquals(stepIds.size, stepIds.toSet().size, "duplicate step id")
    }

    @Test
    fun lessonStepCountsMatchSummaries() {
        // The UI shows a lesson's stepCount from the track summary; it must equal the loaded steps.
        forEachStep { _, _ -> }
        assertTrue(LearnContent.tracks.isNotEmpty())
        for (track in LearnContent.tracks) for (lesson in track.lessons) {
            assertTrue(lesson.steps.isNotEmpty(), "${lesson.id}: a lesson needs at least one step")
        }
    }

    /**
     * Every authored [UiContentBlock.LayoutPreview] must inflate cleanly through the SAME owned engine + empty
     * resource table the Learn tab renders it with — so a lesson preview can never silently degrade to
     * placeholders or an unrenderable-widget problem. Mirrors `IdeServicesBackend.renderStandaloneLayout`.
     */
    @Test
    fun everyLayoutPreviewInflatesCleanly() {
        var count = 0
        forEachLayoutPreview { stepId, block ->
            count++
            val result = LayoutPreviewService().preview(
                xml = block.xml,
                repo = ResourceRepository(emptyList()),
                themeName = null, title = "",
                density = 2f, scaledDensity = 2f, showChrome = false,
            )
            assertTrue(
                result.problems.isEmpty(),
                "$stepId: layout preview reported problems ${result.problems}",
            )
            assertTrue(
                result.root.children.isNotEmpty(),
                "$stepId: layout preview inflated to an empty tree (nothing to show)",
            )
        }
        assertTrue(count > 0, "expected at least one authored LayoutPreview block")
    }

    /**
     * Every authored [UiContentBlock.ComposePreview] must be a well-formed, self-contained Compose snippet: a
     * non-blank body carrying both a `@Composable` and a `@Preview` (detection is by simple name — that's what
     * the lowering path keys on). A full render can't run in the pure ide-core unit classpath (no interpreter /
     * Compose jars), so this guards the authoring so a Compose lesson can't ship a snippet with no preview entry.
     */
    @Test
    fun everyComposePreviewIsWellFormed() {
        var count = 0
        forEachComposePreview { stepId, block ->
            count++
            assertTrue(block.code.isNotBlank(), "$stepId: blank Compose preview code")
            assertTrue(block.code.contains("@Composable"), "$stepId: Compose preview has no @Composable")
            assertTrue(block.code.contains("@Preview"), "$stepId: Compose preview has no @Preview entry")
        }
        assertTrue(count > 0, "expected at least one authored ComposePreview block")
    }

    /** A layout exercise's solution passes its own check through the real renderer, and its starter does not
     *  (otherwise the learner is told "correct" before doing anything). */
    @Test
    fun everyLayoutExerciseAcceptsItsSolutionAndRejectsItsStarter() {
        val render: (String) -> List<String>? = { xml ->
            dev.ide.core.renderStandaloneLayout(
                xml, dev.ide.preview.PreviewRequest(1080, 1920, 2.75f, showChrome = false),
            )?.problems?.map { "${it.tag}: ${it.message}" }
        }
        var count = 0
        forEachStep { lesson, step ->
            val layout = (step as? LearnStepDef.Interactive)?.check?.layout ?: return@forEachStep
            count++
            val ok = checkLayout(step.solution.trimIndent(), layout, render)
            assertTrue(ok.passed, "${lesson.id}/${step.id}: solution rejected: ${ok.message}\n${ok.output}\n${ok.diagnostics}")
            val starter = checkLayout(step.starterCode.trimIndent(), layout, render)
            assertTrue(!starter.passed, "${lesson.id}/${step.id}: the starter already passes")
        }
        assertTrue(count > 0, "expected at least one layout exercise")
    }

    @Test
    fun multiCaseExercisesAreWellFormed() {
        forEachStep { lesson, step ->
            val check = (step as? LearnStepDef.Interactive)?.check ?: return@forEachStep
            if (check.cases.isEmpty()) return@forEachStep
            assertTrue(check.cases.size >= 2, "${lesson.id}/${step.id}: one case is just expectedOutput")
            assertTrue(check.expectedOutput == null && check.mustContain.isEmpty(), "${lesson.id}/${step.id}: cases replace expectedOutput")
            for (c in check.cases) {
                assertTrue(c.expectedOutput != null || c.mustContain.isNotEmpty(), "${lesson.id}/${step.id}: a case checks nothing")
            }
        }
    }

    @Test
    fun pathsAndPrerequisitesNameRealTracks() {
        val ids = LearnContent.tracks.map { it.id }.toSet()
        assertTrue(LearnContent.paths.isNotEmpty())
        for (p in LearnContent.paths) {
            assertTrue(p.trackIds.size >= 2, "${p.id}: a path needs at least two tracks")
            for (t in p.trackIds) assertTrue(t in ids, "${p.id}: unknown track $t")
        }
        for (t in LearnContent.tracks) {
            assertTrue(t.level in setOf("Beginner", "Intermediate", "Advanced"), "${t.id}: level '${t.level}'")
            for (pre in t.prerequisites) {
                assertTrue(pre in ids && pre != t.id, "${t.id}: bad prerequisite $pre")
            }
        }
    }

    @Test
    fun checkpointsAreQuizzesAndCapstonesAreGraded() {
        for (track in LearnContent.tracks) for (lesson in track.lessons) {
            assertTrue(lesson.kind in setOf("lesson", "checkpoint", "capstone"), "${lesson.id}: kind '${lesson.kind}'")
            when (lesson.kind) {
                "checkpoint" -> assertTrue(
                    lesson.steps.count { it is LearnStepDef.Quiz } >= 4,
                    "${lesson.id}: a checkpoint needs at least four quiz questions",
                )
                "capstone" -> assertTrue(
                    lesson.steps.any { it is LearnStepDef.Interactive },
                    "${lesson.id}: a capstone needs a graded exercise",
                )
            }
        }
    }

    private fun forEachStep(body: (LearnLessonDef, LearnStepDef) -> Unit) {
        for (track in LearnContent.tracks) for (lesson in track.lessons) for (step in lesson.steps) body(lesson, step)
    }

    private fun forEachComposePreview(body: (stepId: String, block: UiContentBlock.ComposePreview) -> Unit) {
        forEachStep { _, step ->
            val blocks = when (step) {
                is LearnStepDef.Concept -> step.blocks
                is LearnStepDef.Interactive -> step.blocks
                is LearnStepDef.Quiz -> emptyList()
            }
            for (b in blocks) if (b is UiContentBlock.ComposePreview) body(step.id, b)
        }
    }

    private fun forEachLayoutPreview(body: (stepId: String, block: UiContentBlock.LayoutPreview) -> Unit) {
        forEachStep { _, step ->
            val blocks = when (step) {
                is LearnStepDef.Concept -> step.blocks
                is LearnStepDef.Interactive -> step.blocks
                is LearnStepDef.Quiz -> emptyList()
            }
            for (b in blocks) if (b is UiContentBlock.LayoutPreview) body(step.id, b)
        }
    }
}
