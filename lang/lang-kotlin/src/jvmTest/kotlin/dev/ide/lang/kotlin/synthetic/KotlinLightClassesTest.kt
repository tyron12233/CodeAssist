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
}
