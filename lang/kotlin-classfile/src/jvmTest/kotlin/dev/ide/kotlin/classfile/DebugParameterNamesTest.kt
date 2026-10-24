package dev.ide.kotlin.classfile

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parameter names off the `LocalVariableTable`, the only place a jar built with javac's default `-g` keeps
 * them (`MethodParameters` needs `-parameters`, which almost no library passes). Without this a Kotlin call
 * into `ContextCompat.getSystemService` showed `(Context, Class<T>)` where the Java editor showed the names.
 */
class DebugParameterNamesTest {

    private val source = """
        package p;
        public abstract class Sample {
            public Sample(String id, CharSequence name, int importance) {}
            public void wide(long first, double second, String third) { String local = third; }
            public static int twice(int value, Object other) { return value * 2; }
            public abstract void noBody(String gone);
            public void none() {}
            public class Inner { public Inner(int count) {} }
        }
    """.trimIndent()

    private fun compile(vararg options: String): Map<String, ClassFile> {
        val dir = Files.createTempDirectory("lvt").toFile()
        val src = File(dir, "Sample.java").apply { writeText(source) }
        val javac = ToolProvider.getSystemJavaCompiler()
        val rc = javac.run(null, null, null, *options, "-d", dir.path, src.path)
        assertEquals(0, rc, "javac failed")
        return File(dir, "p").listFiles()!!.filter { it.name.endsWith(".class") }
            .associate { it.name.removeSuffix(".class") to ClassFile.read(it.readBytes())!! }
    }

    private fun names(cf: ClassFile, method: String): List<String> =
        cf.methods.single { it.name == method }.debugParameterNames

    @Test
    fun readsNamesFromTheLocalVariableTable() {
        val sample = compile("-g").getValue("Sample")
        assertEquals(listOf("id", "name", "importance"), names(sample, "<init>"))
        // Two-slot `long`/`double` and a body local that must not be taken for a parameter.
        assertEquals(listOf("first", "second", "third"), names(sample, "wide"))
        // A static method's parameters start at slot 0.
        assertEquals(listOf("value", "other"), names(sample, "twice"))
        assertEquals(emptyList(), names(sample, "noBody"))
        assertEquals(emptyList(), names(sample, "none"))
        assertEquals(emptyList(), sample.methods.single { it.name == "wide" }.parameterNames)
    }

    @Test
    fun anInnerClassConstructorKeepsItsListPositional() {
        // The outer instance comes first in the descriptor; whatever the table says for it, the result must
        // either name every descriptor parameter or none.
        val ctor = compile("-g").getValue("Sample\$Inner").methods.single { it.name == "<init>" }
        val names = ctor.debugParameterNames
        assert(names.isEmpty() || names.size == 2) { "positional with (Outer, int), got $names" }
        if (names.isNotEmpty()) assertEquals("count", names[1])
    }

    @Test
    fun nothingWithoutDebugInfo() {
        val sample = compile("-g:none").getValue("Sample")
        assertEquals(emptyList(), names(sample, "<init>"))
        assertEquals(emptyList(), names(sample, "wide"))
    }
}
