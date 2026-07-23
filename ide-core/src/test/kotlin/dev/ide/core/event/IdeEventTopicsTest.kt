package dev.ide.core.event

import dev.ide.index.IndexStatus
import dev.ide.platform.impl.MessageBusImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trips each [IdeEventTopics] topic over a real bus: the sealed payloads and the `fun interface`
 *  listeners must proxy correctly (this is what the engine/backend publish sites drive). */
class IdeEventTopicsTest {

    @Test
    fun `editor topic delivers events in order`() {
        val bus = MessageBusImpl()
        val got = mutableListOf<EditorEvent>()
        bus.connect().subscribe(IdeEventTopics.EDITOR, EditorEventListener { got.add(it) })

        val pub = bus.syncPublisher(IdeEventTopics.EDITOR)
        pub.onEditorEvent(EditorEvent.FileOpened("/a.kt"))
        pub.onEditorEvent(EditorEvent.SelectionChanged("/a.kt", 3, 7))
        pub.onEditorEvent(EditorEvent.ActiveEditorChanged(null))

        assertEquals(
            listOf(
                EditorEvent.FileOpened("/a.kt"),
                EditorEvent.SelectionChanged("/a.kt", 3, 7),
                EditorEvent.ActiveEditorChanged(null),
            ),
            got,
        )
    }

    @Test
    fun `build run project indexing topics deliver their payloads`() {
        val bus = MessageBusImpl()
        val builds = mutableListOf<BuildEvent>()
        val runs = mutableListOf<RunEvent>()
        val projects = mutableListOf<ProjectEvent>()
        val indexing = mutableListOf<IndexEvent>()
        bus.connect().also { c ->
            c.subscribe(IdeEventTopics.BUILD, BuildEventListener { builds.add(it) })
            c.subscribe(IdeEventTopics.RUN, RunEventListener { runs.add(it) })
            c.subscribe(IdeEventTopics.PROJECT, ProjectEventListener { projects.add(it) })
            c.subscribe(IdeEventTopics.INDEXING, IndexEventListener { indexing.add(it) })
        }

        bus.syncPublisher(IdeEventTopics.BUILD).onBuildEvent(BuildEvent.Started("app", listOf("compileJava", "jar")))
        bus.syncPublisher(IdeEventTopics.BUILD).onBuildEvent(BuildEvent.Finished("app", succeeded = false, failureKind = "compile", message = "boom"))
        bus.syncPublisher(IdeEventTopics.RUN).onRunEvent(RunEvent.Started("app", "Main"))
        bus.syncPublisher(IdeEventTopics.RUN).onRunEvent(RunEvent.Finished("app", exitCode = 0, succeeded = true))
        bus.syncPublisher(IdeEventTopics.PROJECT).onProjectEvent(ProjectEvent.Opened("/proj"))
        bus.syncPublisher(IdeEventTopics.INDEXING).onIndexEvent(IndexEvent.Started)
        bus.syncPublisher(IdeEventTopics.INDEXING).onIndexEvent(IndexEvent.Finished(IndexStatus(building = false, ready = true)))

        assertEquals(BuildEvent.Started("app", listOf("compileJava", "jar")), builds[0])
        assertEquals(BuildEvent.Finished("app", succeeded = false, failureKind = "compile", message = "boom"), builds[1])
        assertEquals(listOf(RunEvent.Started("app", "Main"), RunEvent.Finished("app", 0, true)), runs)
        assertEquals(ProjectEvent.Opened("/proj"), projects.single())
        assertEquals<IndexEvent>(IndexEvent.Started, indexing[0])
        assertTrue((indexing[1] as IndexEvent.Finished).status.ready)
    }
}
