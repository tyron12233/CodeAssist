package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for `no readable property List on Icons$AutoMirrored$Filled`: a material icon like
 * `Icons.AutoMirrored.Filled.List` is an EXTENSION property whose receiver is a chain of NESTED objects
 * (`Icons.AutoMirrored.Filled`). The resolver couldn't infer that nested-object receiver's type (nested
 * objects are classifiers, not members), so the property fell back to a plain member binding and the
 * interpreter looked for a non-existent `getList()` instance getter. Now the receiver chain types, so the
 * property binds as an extension carrying its `…Kt` facade — which the interpreter reflects as
 * `FacadeKt.getList(receiver)`. Exercised against the fake `FakeIcons.AutoMirrored.Filled.FakeListIcon`
 * compiled into the test classpath (no real Compose toolchain needed).
 */
class KotlinNestedObjectExtensionTest {

    @Test
    fun iconExtensionPropertyOnNestedObjectChainBindsAsExtension() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(buildFakeIconsJar()))
        val code = """
            package demo
            import dev.ide.fakecompose.FakeIcons
            import dev.ide.fakecompose.FakeListIcon
            fun f() { FakeIcons.AutoMirrored.Filled.FakeListIcon }
        """.trimIndent()
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        val get = assertIs<RNode.PropertyGet>(assertIs<RNode.Block>(fn.body).statements[0])
        val prop = assertIs<Binding.Property>(get.binding)
        assertEquals("FakeListIcon", prop.name)
        assertTrue(prop.isExtension, "an icon on a nested-object chain must bind as an extension property")
        assertEquals("dev.ide.fakecompose.FakeComposablesKt", prop.ownerFqn, "owner should be the declaring …Kt facade")
        assertTrue(fn.isComplete, "the whole reference should lower completely; diags=${fn.diagnostics}")
    }

    // --- helpers ---

    private fun classBytes(path: String): ByteArray =
        assertNotNull(javaClass.classLoader.getResourceAsStream(path), "missing class resource $path").use { it.readBytes() }

    /** Stage `FakeComposablesKt` (carries the `FakeListIcon` extension) + the `FakeIcons` nested-object classes
     *  into a jar the symbol service can scan (a `kotlin_module` makes it look like a Kotlin library). */
    private fun buildFakeIconsJar(): Path {
        val jar = Files.createTempFile("fake-icons", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                zos.putNextEntry(ZipEntry(name)); zos.write(classBytes(name)); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeComposablesKt.class")
            add("dev/ide/fakecompose/FakeIcons.class")
            add("dev/ide/fakecompose/FakeIcons\$AutoMirrored.class")
            add("dev/ide/fakecompose/FakeIcons\$AutoMirrored\$Filled.class")
        }
        return jar
    }
}
