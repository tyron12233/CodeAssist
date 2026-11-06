package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * False errors reported on a small Android game (a Flappy-style `GameView` driven from `MainActivity`), all on
 * code that compiles. The android.jar shapes involved are reproduced by a tiny ASM fixture jar, the same way
 * [JavaGenericsInferenceTest] does, so the test needs no SDK.
 */
class AndroidGameFalsePositivesTest {

    @Test
    fun findViewByIdAssignedToALateinitPropertyInfersTheTargetType() {
        // `<T extends View> T findViewById(int)` with T pinned by the ASSIGNMENT target, not only by a typed
        // declaration: `gv = findViewById(...)` was "inferred type is View but GameView was expected".
        val code = """
            package demo
            class MainActivity : g.Activity() {
                private lateinit var gv: GameView; private lateinit var st: g.TextView; private lateinit var ss: g.LinearLayout
                fun onCreate() {
                    gv = findViewById(1); st = findViewById(2); ss = findViewById(4)
                    gv.setCallbacks({ s: Int -> runOnUiThread { st.text = s.toString() } }, { s: Int, hs: Int -> runOnUiThread { st.text = (s + hs).toString() } })
                    findViewById<g.Button>(5).setOnClickListener { ss.visibility = g.View.GONE; gv.startGame() }
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), errors("MainActivity.kt", code))
    }

    @Test
    fun anAssignmentToAnUnrelatedTypeIsStillAMismatch() {
        // The expected type pins T, but T must still satisfy its bound: a String target is no View.
        val code = """
            package demo
            class A : g.Activity() {
                private lateinit var name: String
                fun f() { name = findViewById(1) }
            }
        """.trimIndent()
        assertTrue(errors("A.kt", code).any { it.contains("kt.typeMismatch") }, "a non-View target can't take findViewById")
    }

    @Test
    fun nullMayBeAssignedToAJavaBeanProperty() {
        // `Paint.setShader(Shader)` is a Java setter: its parameter is a platform type, so `paint.shader = null`
        // (how a gradient is cleared) is valid Kotlin. Also through an implicit receiver in a Java subclass.
        val code = """
            package demo
            fun draw(paint: g.Paint) {
                paint.shader = g.LinearGradient()
                paint.shader = null
            }
            class Sub : g.Paint() { fun clear() { shader = null } }
        """.trimIndent()
        assertEquals(emptyList(), errors("Paint.kt", code))
    }

    @Test
    fun nullIsStillRejectedForAKotlinNonNullProperty() {
        val code = """
            package demo
            class Holder { var shader: g.Shader = g.Shader() }
            fun f(h: Holder) { h.shader = null }
        """.trimIndent()
        assertTrue(errors("Holder.kt", code).any { it.contains("Null can not be a value") }, "a Kotlin non-null property rejects null")
    }

    @Test
    fun aMutableListIteratorCanRemove() {
        // MutableList.iterator() is MutableCollection's override returning MutableIterator; the resolver took
        // Collection.iterator(): Iterator (found first, through List) and `it.remove()` was unresolved.
        val code = """
            package demo
            class Pipe(var x: Float)
            fun update(pipes: MutableList<Pipe>) {
                val it = pipes.iterator()
                while (it.hasNext()) { val p = it.next(); p.x -= 4.2f; if (p.x < -50) it.remove() }
                val more = mutableListOf<Pipe>()
                val i2 = more.iterator()
                while (i2.hasNext()) { if (i2.next().x < 0) i2.remove() }
            }
        """.trimIndent()
        assertEquals(emptyList(), errors("Iter.kt", code))
    }

    @Test
    fun aReadOnlyListIteratorStillCannotRemove() {
        val code = """
            package demo
            fun f(xs: List<String>) { val it = xs.iterator(); it.remove() }
        """.trimIndent()
        assertTrue(errors("ReadOnly.kt", code).any { it.contains("remove") }, "List.iterator() is a read-only Iterator")
    }

    private fun errors(name: String, code: String): List<String> =
        diagnostics(name, code).filter { it.severity == Severity.ERROR }
            .map { "${it.code} '${code.substring(it.range.start, it.range.end)}' ${it.message}" }

    private fun diagnostics(name: String, code: String) = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(name)))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics
    }

    companion object {
        /** The game's own view, on disk like any other file of the project. */
        val srcDir: Path = tempProject(mapOf(
            "GameView.kt" to """
                package demo
                class GameView : g.View() {
                    fun setCallbacks(onScore: (Int) -> Unit, onGameOver: (Int, Int) -> Unit) {}
                    fun startGame() {}
                }
            """.trimIndent(),
        ))
        val fixtureJar: Path = buildFixtureJar()
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fixtureJar)))

        private const val OBJ = "java/lang/Object"
        private const val V = Opcodes.V1_8
        private const val PUB = Opcodes.ACC_PUBLIC

        private fun cls(name: String, superName: String = OBJ, body: ClassWriter.() -> Unit = {}): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, name, null, superName, null)
            cw.visitMethod(PUB, "<init>", "()V", null, null).visitEnd()
            cw.body()
            cw.visitEnd()
            return cw.toByteArray()
        }

        private fun ClassWriter.method(name: String, desc: String, sig: String? = null, access: Int = PUB) =
            visitMethod(access, name, desc, sig, null).visitEnd()

        private fun buildFixtureJar(): Path {
            val classes = linkedMapOf(
                // android.view.View: GONE, setVisibility/getVisibility, setOnClickListener(OnClickListener).
                "g/View" to cls("g/View") {
                    visitField(PUB or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "GONE", "I", null, 8).visitEnd()
                    method("setVisibility", "(I)V"); method("getVisibility", "()I")
                    method("setOnClickListener", "(Lg/View\$OnClickListener;)V")
                },
                "g/View\$OnClickListener" to ClassWriter(0).run {
                    visit(V, PUB or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE, "g/View\$OnClickListener", null, OBJ, null)
                    visitInnerClass("g/View\$OnClickListener", "g/View", "OnClickListener",
                        PUB or Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE)
                    method("onClick", "(Lg/View;)V", access = PUB or Opcodes.ACC_ABSTRACT)
                    visitEnd(); toByteArray()
                },
                "g/Button" to cls("g/Button", "g/View"),
                "g/LinearLayout" to cls("g/LinearLayout", "g/View"),
                // android.widget.TextView: setText(CharSequence) / getText(): CharSequence.
                "g/TextView" to cls("g/TextView", "g/View") {
                    method("setText", "(Ljava/lang/CharSequence;)V"); method("getText", "()Ljava/lang/CharSequence;")
                },
                // android.app.Activity: <T extends View> T findViewById(int), runOnUiThread(Runnable).
                "g/Activity" to cls("g/Activity") {
                    method("findViewById", "(I)Lg/View;", "<T:Lg/View;>(I)TT;")
                    method("runOnUiThread", "(Ljava/lang/Runnable;)V")
                },
                // android.graphics.Shader / LinearGradient / Paint: Shader setShader(Shader), Shader getShader().
                "g/Shader" to cls("g/Shader"),
                "g/LinearGradient" to cls("g/LinearGradient", "g/Shader"),
                "g/Paint" to cls("g/Paint") {
                    method("setShader", "(Lg/Shader;)Lg/Shader;"); method("getShader", "()Lg/Shader;")
                },
            )
            val jar = Files.createTempFile("android-game-fixture", ".jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                classes.forEach { (name, bytes) ->
                    out.putNextEntry(JarEntry("$name.class")); out.write(bytes); out.closeEntry()
                }
            }
            return jar
        }
    }
}
