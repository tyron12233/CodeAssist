package dev.ide.lang.kotlin.synthetic

import dev.ide.lang.kotlin.DiskFile
import dev.ide.lang.kotlin.tempProject
import dev.ide.lang.synthetic.SyntheticClass
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [KotlinLightClasses] keeps the Java-visible shapes of Kotlin source current one file at a time: an open
 * buffer is read instead of disk, only changed files are parsed again, and an edit that leaves every shape
 * alone (a function body) hands back the very same result, so the Java side has nothing to invalidate.
 */
class KotlinLightClassesTest {

    private val dir: Path = tempProject(
        mapOf(
            "com/ex/Greeter.kt" to "package com.ex\nclass Greeter { fun hello(): String = \"hi\" }\n",
            "com/ex/Util.kt" to "package com.ex\nfun util(): Int { return 1 }\n",
            "com/ex/Uses.kt" to "package com.ex\nclass Uses { fun make(): Widget? = null }\n",
        )
    )
    private val roots = listOf(DiskFile(dir))
    private val buffers = HashMap<String, String>()
    private val light = KotlinLightClasses()

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun path(rel: String) = DiskFile(dir.resolve(rel)).path
    private fun classes() = light.classesFor("m", roots) { buffers[it] }
    private fun methodsOf(fqn: String, cs: List<SyntheticClass> = classes()) =
        cs.first { it.fqName == fqn }.methods.filterNot { it.isConstructor }.map { it.name }

    @Test
    fun anUnsavedBufferEditIsVisible() {
        assertEquals(listOf("hello"), methodsOf("com.ex.Greeter"))
        buffers[path("com/ex/Greeter.kt")] =
            "package com.ex\nclass Greeter { fun hello(): String = \"hi\"\n fun added(n: Int) {} }\n"
        assertEquals(listOf("hello", "added"), methodsOf("com.ex.Greeter"))
    }

    @Test
    fun aBodyEditParsesOneFileAndKeepsTheResult() {
        val first = classes()
        val parses = light.parseCount
        buffers[path("com/ex/Util.kt")] = "package com.ex\nfun util(): Int { val x = 41; return x + 1 }\n"
        val second = classes()
        assertEquals(parses + 1, light.parseCount, "only the edited file is parsed again")
        assertSame(first, second, "same shapes: the very same list, so a consumer has nothing to drop")
    }

    @Test
    fun nothingChangedParsesNothing() {
        classes()
        val parses = light.parseCount
        classes()
        assertEquals(parses, light.parseCount)
    }

    @Test
    fun aNewClassElsewhereReRendersTheFilesThatNameIt() {
        // `Widget` isn't a class of the module yet, so `make()` returns Object.
        assertEquals("java.lang.Object", classes().first { it.fqName == "com.ex.Uses" }.methods.single { !it.isConstructor }.returnType)
        val parses = light.parseCount
        buffers[path("com/ex/Greeter.kt")] =
            "package com.ex\nclass Greeter { fun hello(): String = \"hi\" }\nclass Widget\n"
        val after = classes()
        assertEquals("com.ex.Widget", after.first { it.fqName == "com.ex.Uses" }.methods.single { !it.isConstructor }.returnType)
        assertEquals(parses + 2, light.parseCount, "the edited file + the one file whose signature names Widget")
    }

    @Test
    fun aSaveIsReadAfterInvalidate() {
        classes()
        val file = dir.resolve("com/ex/Greeter.kt")
        file.writeText("package com.ex\nclass Greeter { fun bye() {} }\n")
        assertEquals(listOf("hello"), methodsOf("com.ex.Greeter"), "a disk entry stands until it is invalidated")
        light.invalidate(path("com/ex/Greeter.kt"))
        assertEquals(listOf("bye"), methodsOf("com.ex.Greeter"))
    }

    @Test
    fun closingABufferFallsBackToDisk() {
        buffers[path("com/ex/Greeter.kt")] = "package com.ex\nclass Greeter { fun unsaved() {} }\n"
        assertEquals(listOf("unsaved"), methodsOf("com.ex.Greeter"))
        buffers.clear()
        assertEquals(listOf("hello"), methodsOf("com.ex.Greeter"))
    }

    @Test
    fun rescanPicksUpANewFileWithoutReparsingTheRest() {
        val before = classes()
        val parses = light.parseCount
        dir.resolve("com/ex/Extra.kt").writeText("package com.ex\nclass Extra\n")
        light.rescan()
        val after = classes()
        assertTrue(after.any { it.fqName == "com.ex.Extra" })
        assertEquals(parses + 1, light.parseCount, "only the new file is parsed")
        assertNotSame(before, after)
    }

    private fun returnTypes(source: String): Map<String, String> {
        buffers[path("com/ex/Util.kt")] = source
        return classes().first { it.fqName == "com.ex.UtilKt" }.methods.associate { it.name to it.returnType }
    }

    @Test
    fun aTypeIsInferredOnlyFromWhatTheInitializerEvidentlyIs() {
        val r = returnTypes(
            """
            package com.ex
            val s = "x"
            val i = 1_000
            val l = 3L
            val d = 1.5
            val f = 2f
            val b = false
            val g = Greeter()
            val xs = listOf(1)
            val ident = f
            val sum = i + 1
            val chained = Greeter().hello()
            val call = compute(1)
            fun compute(n: Int) = n
            """.trimIndent()
        )
        assertEquals("java.lang.String", r["getS"]); assertEquals("int", r["getI"]); assertEquals("long", r["getL"])
        assertEquals("double", r["getD"]); assertEquals("float", r["getF"]); assertEquals("boolean", r["getB"])
        assertEquals("com.ex.Greeter", r["getG"], "a constructor call of a module class")
        assertEquals("java.util.List", r["getXs"])
        for (name in listOf("getIdent", "getSum", "getChained", "getCall", "compute")) {
            assertEquals("java.lang.Object", r[name], "$name: not evident from the text, so not guessed")
        }
    }

    @Test
    fun functionTypesBecomeKotlinFunctionInterfaces() {
        val r = returnTypes(
            """
            package com.ex
            fun a(): (String) -> Unit = TODO()
            fun b(): ((Int, Int) -> String)? = null
            fun c(): String.(Int) -> Boolean = TODO()
            fun d(): suspend () -> Unit = TODO()
            fun e(): () -> List<String> = TODO()
            """.trimIndent()
        )
        assertEquals("kotlin.jvm.functions.Function1<java.lang.String, kotlin.Unit>", r["a"])
        assertEquals("kotlin.jvm.functions.Function2<java.lang.Integer, java.lang.Integer, java.lang.String>", r["b"])
        assertEquals("kotlin.jvm.functions.Function2<java.lang.String, java.lang.Integer, java.lang.Boolean>", r["c"], "the receiver comes first")
        assertEquals("java.lang.Object", r["d"], "a suspend function type compiles to something else")
        assertEquals("kotlin.jvm.functions.Function0<java.util.List<java.lang.String>>", r["e"])
    }
}
