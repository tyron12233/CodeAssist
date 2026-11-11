package dev.ide.lang.java

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.PlatformApiLevels
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Java API that Android does not have is explained, not just rejected (`Files.writeString`, Java 11, absent from
 * Android before API 37). The platform here is a stub `javax.demo.Store` that lacks what the JDK table lists.
 */
class JavaApiMissingOnAndroidTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    private val android = object : PlatformApiLevels {
        override val platformLevel = 36
        override fun classLevel(className: String): ApiLevel? = null
        override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? = null
        override fun fieldLevel(ownerClass: String, name: String): ApiLevel? = null
    }

    /** The JDK as far as this test needs it: `Store.writeAll` arrived in Java 11, `Store.LIMIT` was always there. */
    private val jdk = object : PlatformApiLevels {
        override val platformLevel = 25
        override fun classLevel(className: String): ApiLevel? = if (className == "javax.demo.gone.Client") ApiLevel(11) else null
        override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? =
            if (ownerClass == "javax.demo.Store" && name == "writeAll") ApiLevel(11) else null
        override fun fieldLevel(ownerClass: String, name: String): ApiLevel? =
            if (ownerClass == "javax.demo.Store" && name == "LIMIT") ApiLevel(8) else null
    }

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "javax/demo/Store.java").apply { parentFile.mkdirs() }
            .writeText("package javax.demo;\npublic class Store { public static void keep(String s) {} public void close() {} }")
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun unresolved(body: String, context: ApiLevelContext?): List<String> {
        val src = "package com.foo;\nimport javax.demo.Store;\nclass Use {\n$body\n}\n"
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs() }
        f.writeText(src)
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, apiContext = { context })
        return parsed.diagnostics.filter { it.code == JavaDiagnosticCodes.UNRESOLVED }.map { it.message }
    }

    private val explained get() = ApiLevelContext.android(21, android) { jdk }

    @Test
    fun aStaticCallToJavaApiAndroidLacksIsExplained() {
        val messages = unresolved("void m() { Store.keep(\"a\"); Store.writeAll(\"a\"); }", explained)
        assertEquals(listOf("Cannot resolve symbol 'writeAll'. 'Store.writeAll' is Java 11 API that Android API 36 does not have."), messages)
    }

    @Test
    fun aCallOnAnInstanceAndAFieldAreExplainedToo() {
        val messages = unresolved("void m(Store s) { s.close(); s.writeAll(\"a\"); int n = Store.LIMIT; }", explained)
        assertEquals(2, messages.size, messages.toString())
        assertTrue(messages.any { it == "Cannot resolve symbol 'writeAll'. 'Store.writeAll' is Java 11 API that Android API 36 does not have." }, messages.toString())
        // Java 8 or earlier: the table cannot name a release, so none is claimed.
        assertTrue(messages.any { it == "Cannot resolve symbol 'LIMIT'. 'Store.LIMIT' is Java API that Android API 36 does not have." }, messages.toString())
    }

    private fun unresolvedIn(src: String): List<String> {
        val f = File(srcRoot, "com/foo/Imp.java").apply { parentFile.mkdirs() }
        f.writeText(src)
        val parsed = JavaParsedFile(env.parse("com/foo/Imp.java", src), fs.fileFor(f.toPath()), 1L, apiContext = { explained })
        return parsed.diagnostics.filter { it.code == JavaDiagnosticCodes.UNRESOLVED }.map { it.message }
    }

    @Test
    fun anImportOfAJavaClassAndroidLacksIsExplained() {
        val messages = unresolvedIn("package com.foo;\nimport javax.demo.gone.Client;\nclass Imp {}\n")
        assertEquals(listOf("Cannot resolve symbol 'gone'. 'javax.demo.gone.Client' is Java 11 API that Android API 36 does not have."), messages)
    }

    @Test
    fun aWholePackageAndroidLeavesOutIsExplainedWithWhatToUse() {
        // The shared table directly: this test's platform is the host JDK, which has these packages.
        val missing = dev.ide.lang.resolve.JavaApiMissingOnAndroid
        assertEquals("'java.net.http' is Java API that Android API 36 does not have.", missing.explainType(explained, "java.net.http"))
        assertEquals("Use 'java.net.HttpURLConnection', or a library such as OkHttp.", missing.alternative("java.net.http.HttpClient"))
        assertEquals("Use 'java.net.HttpURLConnection', or a library such as OkHttp.", missing.alternative("java.net.http.HttpClient.newHttpClient"))
        assertEquals(null, missing.alternative("java.util.List.of"))
        assertEquals(null, missing.explainType(explained, "com.example.Thing"))
    }

    @Test
    fun whatTheJdkDoesNotHaveEitherIsJustUnresolved() {
        assertEquals(listOf("Cannot resolve symbol 'nope'"), unresolved("void m() { Store.nope(); }", explained))
    }

    @Test
    fun withoutTheJdkTableOrOutsideAndroidTheMessageIsUnchanged() {
        val body = "void m() { Store.writeAll(\"a\"); }"
        assertEquals(listOf("Cannot resolve symbol 'writeAll'"), unresolved(body, ApiLevelContext.android(21, android)))
        assertEquals(listOf("Cannot resolve symbol 'writeAll'"), unresolved(body, ApiLevelContext.jdk(17, jdk, enforced = false)))
        assertEquals(listOf("Cannot resolve symbol 'writeAll'"), unresolved(body, null))
    }
}
