package dev.ide.model.impl.format

import kotlin.test.Test
import kotlin.test.assertEquals

class TomlTest {

    @Test
    fun roundTripsAModuleLikeDocument() {
        val doc = linkedMapOf(
            "version" to 1L,
            "module" to linkedMapOf(
                "type" to "java-lib",
                "name" to "core",
                "languageLevel" to "JAVA_17",
                "output" to "build/classes",
            ),
            "sourceSets" to linkedMapOf(
                "main" to linkedMapOf("scope" to "IMPLEMENTATION", "java" to listOf("src/main/java")),
                "test" to linkedMapOf("scope" to "TEST_IMPLEMENTATION", "java" to listOf("src/test/java")),
            ),
            "dependencies" to linkedMapOf(
                "api" to listOf(linkedMapOf("module" to "shared")),
                "implementation" to listOf("com.squareup.okhttp3:okhttp:4.12.0", linkedMapOf("sdk" to "jdk-17")),
            ),
            "java" to linkedMapOf("annotationProcessors" to listOf("dagger.Component"), "preview" to false),
        )
        assertEquals(doc, Toml.parse(Toml.write(doc)))
    }

    @Test
    fun toleratesCommentsBlankLinesAndTrailingComments() {
        val text = """
            # a comment
            version = 1

            [module]
            name = "core"   # trailing comment
            type = "java-lib"

            [java]
            procs = ["a", "b"]
        """.trimIndent()
        val doc = Toml.parse(text)
        assertEquals(1L, doc["version"])
        assertEquals("core", (doc["module"] as Map<*, *>)["name"])
        assertEquals(listOf("a", "b"), (doc["java"] as Map<*, *>)["procs"])
    }

    @Test
    fun parsesArraysMixingStringsAndInlineTables() {
        val doc = Toml.parse("""x = ["s", { module = "m" }, { sdk = "jdk-17" }]""")
        val arr = doc["x"] as List<*>
        assertEquals("s", arr[0])
        assertEquals(linkedMapOf("module" to "m"), arr[1])
        assertEquals(linkedMapOf("sdk" to "jdk-17"), arr[2])
    }

    @Test
    fun parsesMultilineArrays() {
        val text = """
            x = [
              "a",
              "b",
            ]
        """.trimIndent()
        assertEquals(listOf("a", "b"), Toml.parse(text)["x"])
    }
}
