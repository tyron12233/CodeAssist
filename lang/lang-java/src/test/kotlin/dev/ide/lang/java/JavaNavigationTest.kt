package dev.ide.lang.java

import dev.ide.lang.java.completion.JavaCompletion
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.navigate.JavaNavKind
import dev.ide.lang.java.navigate.JavaNavLocation
import dev.ide.lang.java.navigate.JavaNavTarget
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaNavigationTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    private val repo = """
        package com.notes;

        public interface NoteRepository {
            void save(Note note);
            void save(Note note, boolean flush);
            Note find(long id);
        }
    """.trimIndent()

    private val note = """
        package com.notes;

        public class Note {
            public final String title;
            public Note(String title) { this.title = title; }
        }
    """.trimIndent()

    private val roomRepo = """
        package com.notes;

        public class RoomNoteRepository implements NoteRepository {
            @Override public void save(Note note) { }
            @Override public void save(Note note, boolean flush) { }
            @Override public Note find(long id) { return null; }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-nav").toFile()
        File(srcRoot, "com/notes").mkdirs()
        File(srcRoot, "com/notes/NoteRepository.java").writeText(repo)
        File(srcRoot, "com/notes/Note.java").writeText(note)
        File(srcRoot, "com/notes/RoomNoteRepository.java").writeText(roomRepo)
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        analyzer.subtypeIndexQuery = { fqn ->
            if (fqn == "com.notes.NoteRepository") listOf(JavaCompletion.IndexedType("com.notes.RoomNoteRepository", "class"))
            else emptyList()
        }
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private val use = """
        package com.notes;

        import java.util.ArrayList;
        import java.util.List;

        public class NotesViewModel {
            private final NoteRepository repository;
            private final List<Note> notes = new ArrayList<>();

            public NotesViewModel(NoteRepository repository) { this.repository = repository; }

            public void add(String title) {
                Note note = new Note(title);
                repository.save(note);
                notes.add(note);
                helper();
            }

            private void helper() { }

            @Override
            public String toString() { return "vm"; }
        }
    """.trimIndent()

    private fun useFile(): VirtualFile {
        val f = File(srcRoot, "com/notes/NotesViewModel.java")
        f.writeText(use)
        return fs.fileFor(f.toPath())
    }

    private fun at(text: String, needle: String, nth: Int = 0): Int {
        var i = -1
        repeat(nth + 1) { i = text.indexOf(needle, i + 1) }
        check(i >= 0) { "'$needle' not in text" }
        return i + 1
    }

    private fun nav(needle: String, kind: JavaNavKind, nth: Int = 0): List<JavaNavTarget> =
        analyzer.navigationTargets(useFile(), use, at(use, needle, nth), kind)

    private fun sourceOffset(t: JavaNavTarget, file: String): Int {
        val loc = t.location as? JavaNavLocation.SourceFile ?: error("not a source location: $t")
        assertTrue(loc.path.endsWith(file), "expected $file, got ${loc.path}")
        return loc.offset
    }

    @Test
    fun declarationInAnotherFileLandsOnTheName() {
        val t = nav("Note note = new", JavaNavKind.DECLARATION).single()
        assertEquals(note.indexOf("class Note") + "class ".length, sourceOffset(t, "Note.java"))
        assertEquals("class", t.kind)
    }

    @Test
    fun newExpressionGoesToTheConstructor() {
        val t = nav("Note(title)", JavaNavKind.DECLARATION).single()
        assertEquals(note.indexOf("public Note(") + "public ".length, sourceOffset(t, "Note.java"))
        assertEquals("constructor", t.kind)
    }

    @Test
    fun resolvedCallGoesToItsOverload() {
        val t = nav("save(note)", JavaNavKind.DECLARATION).single()
        assertEquals(repo.indexOf("void save(Note note);") + "void ".length, sourceOffset(t, "NoteRepository.java"))
        assertEquals("save(Note)  ·  NoteRepository", t.label)
    }

    @Test
    fun sameFileDeclaration() {
        val t = nav("helper();", JavaNavKind.DECLARATION).single()
        assertEquals(JavaNavLocation.ThisFile(use.indexOf("void helper()") + "void ".length), t.location)
        val field = nav("repository.save", JavaNavKind.DECLARATION).single()
        assertEquals(JavaNavLocation.ThisFile(use.indexOf("repository;") ), field.location)
    }

    @Test
    fun libraryClassAndMember() {
        val cls = nav("ArrayList<>", JavaNavKind.DECLARATION).single()
        assertEquals(JavaNavLocation.Library("java.util.ArrayList", null), cls.location)
        assertEquals("library", cls.kind)
        val member = nav("add(note)", JavaNavKind.DECLARATION)
        assertTrue(member.isNotEmpty(), "List.add should resolve")
        assertEquals(JavaNavLocation.Library("java.util.List", "add"), member.first().location)
    }

    @Test
    fun typeDeclarationOfAVariable() {
        val t = nav("repository.save", JavaNavKind.TYPE_DECLARATION).single()
        assertEquals(repo.indexOf("NoteRepository {"), sourceOffset(t, "NoteRepository.java"))
    }

    @Test
    fun implementationsOfAnInterfaceMethod() {
        val t = nav("save(note)", JavaNavKind.IMPLEMENTATION).single()
        assertEquals(roomRepo.indexOf("void save(Note note)") + "void ".length, sourceOffset(t, "RoomNoteRepository.java"))
    }

    @Test
    fun implementationsOfAType() {
        val t = nav("NoteRepository repository;", JavaNavKind.IMPLEMENTATION).single()
        assertEquals(roomRepo.indexOf("RoomNoteRepository implements"), sourceOffset(t, "RoomNoteRepository.java"))
    }

    @Test
    fun superOfAnOverride() {
        val t = nav("toString()", JavaNavKind.SUPER).single()
        assertEquals(JavaNavLocation.Library("java.lang.Object", "toString"), t.location)
    }

    @Test
    fun optionsListOnlyWhatApplies() {
        val kinds = analyzer.navigationOptions(useFile(), use, at(use, "save(note)")).map { it.first }
        // `void` has no type to go to, and an interface's own method overrides nothing.
        assertEquals(listOf(JavaNavKind.DECLARATION, JavaNavKind.IMPLEMENTATION), kinds)
    }

    @Test
    fun nothingOnWhitespaceOrKeywords() {
        assertEquals(emptyList(), nav("public void add", JavaNavKind.DECLARATION))
    }
}
