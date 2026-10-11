package dev.ide.core

import dev.ide.ui.backend.UiModuleConfigEdit
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plain JVM module's Java language level decides which JDK APIs it may call, through the bundled JDK table:
 * the editor resolves against the running JDK, so without the check a Java 8 module is offered and accepts
 * `List.of`, which its `--release 8` build then rejects.
 */
class JdkApiLevelE2ETest {

    private val root = createTempDirectory("jdk-api-level-e2e")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aJvmModuleReportsJdkApisNewerThanItsLanguageLevel() {
        val s = IdeServices.bootstrapJavaDemo(root, ApplicationEnvironment()).also { services = it }
        val backend = IdeServicesBackend(s)
        val java = "package com.example.app;\n\nclass Use {\n    Object m() { return java.util.List.of(1); }\n}\n"
        val kotlin = "package com.example.app\n\nfun m(a: String?): String = java.util.Objects.requireNonNullElse(a, \"x\")\n"
        val javaFile = write(s, "Use.java", java)
        val kotlinFile = write(s, "Use.kt", kotlin)
        // The Kotlin editor stays in dumb mode, resolving nothing, until the index is ready.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)

        fun messages(level: String): List<String> {
            assertTrue(s.moduleService.updateModuleConfig("app", UiModuleConfigEdit(languageLevel = level)).success)
            return runBlocking {
                backend.editor.analyze(javaFile.toString(), java).map { it.message } +
                    backend.editor.analyze(kotlinFile.toString(), kotlin).map { it.message }
            }.filter { "requires Java" in it }
        }

        assertEquals(
            listOf(
                "Call requires Java 9 (module language level is 8): `java.util.List#of`",
                "Call requires Java 9 (module language level is 8): `java.util.Objects#requireNonNullElse`",
            ),
            messages("JAVA_8"),
        )
        assertEquals(emptyList(), messages("JAVA_11"))
    }

    private fun write(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray())
        services.modules()
        return file
    }
}
