package dev.ide.core

import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.kotlin.NavTarget
import dev.ide.testkit.writeSource
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Go to Declaration / Implementation / Type / Super from a Java file through the real engine: to another Java
 * file on disk, to an open buffer's unsaved text, to the Kotlin declaration behind a light class, and to a
 * compiled library class.
 */
class JavaNavigationE2ETest {

    private val root = createTempDirectory("java-nav-e2e")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private val pkg = "core/src/main/java/com/example/core"

    private val note = """
        package com.example.core;

        public class Note {
            public final String title;
            public Note(String title) { this.title = title; }
        }
    """.trimIndent()

    private val repo = """
        package com.example.core;

        public interface NoteStore {
            void save(Note note);
        }
    """.trimIndent()

    private val fileRepo = """
        package com.example.core;

        public class FileNoteStore implements NoteStore {
            @Override public void save(Note note) { }
        }
    """.trimIndent()

    private val kotlin = """
        package com.example.core

        fun helloWorld() { println("Hello World") }

        class KtGreeter(val name: String) {
            fun hello(): String = "hi " + name
        }
    """.trimIndent()

    private val probe = """
        package com.example.core;

        import java.util.ArrayList;

        class Probe {
            void run(NoteStore store) {
                Note note = new Note("x");
                store.save(note);
                new KtGreeter("a").hello();
                GreetingsKt.helloWorld();
                String n = new KtGreeter("b").getName();
                ArrayList<String> list = new ArrayList<>();
            }
        }
    """.trimIndent()

    private fun bootstrap(): IdeServices {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        root.writeSource("$pkg/Note.java", note, trim = false)
        root.writeSource("$pkg/NoteStore.java", repo, trim = false)
        root.writeSource("$pkg/FileNoteStore.java", fileRepo, trim = false)
        root.writeSource("$pkg/Greetings.kt", kotlin, trim = false)
        val before = s.indexService.status.stats
        s.ensureKotlinStdlib()
        s.invalidateSyntheticClasses()
        s.reindex()
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && s.indexService.status.stats === before) Thread.sleep(50)
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - quietSince < 1_000) {
            val st = s.indexService.status
            if (!st.ready || st.building) quietSince = System.currentTimeMillis()
            Thread.sleep(50)
        }
        return s
    }

    private val probePath: Path get() = root.resolve("$pkg/Probe.java")

    private fun nav(s: IdeServices, anchor: String, kind: NavKind = NavKind.DECLARATION, text: String = probe): List<NavTarget> =
        s.navigationTargets(probePath, text, text.indexOf(anchor) + 1, kind)

    private fun assertLands(t: NavTarget, file: String, offset: Int) {
        assertTrue(t.file.path.endsWith(file), "expected $file, got ${t.file.path}")
        assertEquals(offset, t.offset, "offset in $file")
    }

    @Test
    fun javaFileOnDisk() {
        val s = bootstrap()
        assertLands(nav(s, "Note(\"x\")").single(), "Note.java", note.indexOf("Note(String") )
        assertLands(nav(s, "save(note)").single(), "NoteStore.java", repo.indexOf("save("))
        assertLands(nav(s, "note);").single(), "Probe.java", probe.indexOf("note = new"))
    }

    @Test
    fun unsavedJavaBuffer() {
        val s = bootstrap()
        val edited = note.replace("public final String title;", "public final String title;\n    public int extra() { return 1; }")
        s.updateDocument(root.resolve("$pkg/Note.java"), edited)
        val text = probe.replace("store.save(note);", "store.save(note); note.extra();")
        assertLands(nav(s, "extra()", text = text).single(), "Note.java", edited.indexOf("extra()"))
    }

    @Test
    fun kotlinDeclarations() {
        val s = bootstrap()
        val kt = root.resolve("$pkg/Greetings.kt").readText()
        assertLands(nav(s, "hello();").single(), "Greetings.kt", kt.indexOf("hello()"))
        assertLands(nav(s, "helloWorld();").single(), "Greetings.kt", kt.indexOf("helloWorld"))
        assertLands(nav(s, "getName()").single(), "Greetings.kt", kt.indexOf("name: String"))
    }

    @Test
    fun libraryClass() {
        val s = bootstrap()
        val t = nav(s, "ArrayList<>").single()
        assertEquals("library://java.util.ArrayList", t.file.path)
        val content = s.libraryContent(probePath, "java.util.ArrayList", forceJava = false)
        assertTrue(content != null && "class ArrayList" in content.text, "the library view should open: $content")
    }

    @Test
    fun kotlinToJavaSource() {
        val s = bootstrap()
        val kt = root.resolve("$pkg/Use.kt")
        val text = "package com.example.core\n\nfun use(store: NoteStore) {\n    val n = Note(\"a\")\n    store.save(n)\n    println(n.title)\n}\n"
        fun go(anchor: String) = s.navigationTargets(kt, text, text.indexOf(anchor) + 1, NavKind.DECLARATION)
        assertLands(go("NoteStore)").single(), "NoteStore.java", repo.indexOf("NoteStore {"))
        assertLands(go("save(n)").single(), "NoteStore.java", repo.indexOf("save("))
        assertLands(go("title)").single(), "Note.java", note.indexOf("title;"))
    }

    @Test
    fun implementationsAndSuper() {
        val s = bootstrap()
        assertLands(nav(s, "save(note)", NavKind.IMPLEMENTATION).single(), "FileNoteStore.java", fileRepo.indexOf("save("))
        val options = s.navigationOptions(probePath, probe, probe.indexOf("save(note)") + 1).map { it.first }
        assertEquals(listOf(NavKind.DECLARATION, NavKind.IMPLEMENTATION), options)
        val impl = root.resolve("$pkg/FileNoteStore.java")
        val sup = s.navigationTargets(impl, fileRepo, fileRepo.indexOf("save(") + 1, NavKind.SUPER).single()
        assertLands(sup, "NoteStore.java", repo.indexOf("save("))
    }
}
