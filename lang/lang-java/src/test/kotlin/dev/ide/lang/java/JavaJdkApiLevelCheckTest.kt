package dev.ide.lang.java

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.PlatformApiLevels
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On a plain JVM module the API-level check compares JDK APIs with the module's Java release, as
 * `javac --release` does: the editor resolves against the running JDK, so a newer API resolves fine and only
 * this check says the module's release does not have it.
 */
class JavaJdkApiLevelCheckTest {
    private val srcRoot = Files.createTempDirectory("java-src").toFile()
    private val fs = LocalFileSystem(srcRoot.toPath())
    private val env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))

    /** What the JDK table says about the few APIs below. */
    private val jdk = object : PlatformApiLevels {
        override val platformLevel = 25
        val methods = mapOf(
            "java.util.List#of" to ApiLevel(9),
            "java.lang.String#strip" to ApiLevel(11),
            "java.lang.String#length" to ApiLevel(8),
        )
        override fun classLevel(className: String) = if (className.startsWith("java.")) ApiLevel(8) else null
        override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int) =
            methods["${ownerClass.replace('/', '.')}#$name"]
        override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun check(release: Int, enforced: Boolean = true): List<Diagnostic> {
        val src = """
            package com.foo;
            import java.util.List;
            class Use {
                int m(String s) {
                    List<Integer> xs = List.of(1, 2);
                    return s.strip().length() + xs.size();
                }
            }
        """.trimIndent()
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs(); writeText(src) }
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, apiContext = { ApiLevelContext.jdk(release, jdk, enforced) })
        return parsed.diagnostics.filter { it.code == JavaDiagnosticCodes.NEW_JDK_API }
    }

    @Test
    fun aJdkApiNewerThanTheReleaseIsAnError() {
        val d = check(release = 8)
        assertEquals(
            listOf(
                "Call requires Java 9 (module language level is 8): `java.util.List#of`",
                "Call requires Java 11 (module language level is 8): `java.lang.String#strip`",
            ),
            d.map { it.message },
        )
        assertTrue(d.all { it.severity == Severity.ERROR })
    }

    @Test
    fun aReleaseThatHasTheApiIsClean() {
        assertEquals(listOf("java.lang.String#strip"), check(release = 9).map { it.message.substringAfter('`').removeSuffix("`") })
        assertEquals(emptyList(), check(release = 11))
    }

    @Test
    fun whereTheBuildDoesNotEnforceTheReleaseItIsAWarning() {
        assertTrue(check(release = 8, enforced = false).all { it.severity == Severity.WARNING })
    }
}
