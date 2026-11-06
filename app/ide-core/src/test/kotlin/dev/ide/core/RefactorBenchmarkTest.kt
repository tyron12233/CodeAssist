package dev.ide.core

import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Times Find Usages and Rename on a generated project shaped like a real app: a model used from many Kotlin
 * files, Java code calling into Kotlin and back, and the common names (`name`, `id`, `load`) that many unrelated
 * symbols share, which is what makes a sweep resolve instead of skip. Opt-in: `-Prefactor.bench=true`.
 */
class RefactorBenchmarkTest {

    private val kotlinFiles = 150
    private val javaFiles = 60

    private lateinit var ide: IdeServices

    private fun write(dir: Path, name: String, text: String): Path =
        dir.resolve(name).also { ide.save(it, text) }

    private fun generate(ide: IdeServices): Path {
        this.ide = ide
        val src = ide.sourceRoots(ide.modules().first { it.name == "core" }).first().resolve("com/example/core")
        Files.createDirectories(src)
        write(
            src, "Model.kt",
            """
            package com.example.core

            data class User(val id: Long, val name: String, var email: String)

            fun formatUser(u: User): String = u.name + " <" + u.email + ">"

            object Repo {
                fun load(id: Long): User = User(id, "user" + id, "u" + id + "@example.com")
            }
            """.trimIndent(),
        )
        write(
            src, "Helper.java",
            """
            package com.example.core;

            public class Helper {
                public static int compute(int a, int b) { return a * b + a; }
                public String name() { return "helper"; }
            }
            """.trimIndent(),
        )
        for (i in 0 until kotlinFiles) {
            write(
                src, "Screen$i.kt",
                """
                package com.example.core

                class Item$i(val id: Long, val name: String) {
                    fun label(): String = name + "#" + id
                }

                class Screen$i {
                    private val items = listOf(Item$i(1, "a"), Item$i(2, "b"))
                    fun render(id: Long): String {
                        val user = Repo.load(id)
                        val name = user.name
                        val text = formatUser(user)
                        val total = Helper.compute(items.size, $i)
                        val helperName = Helper().name()
                        return name + text + total + helperName + items.joinToString { it.name + it.label() }
                    }
                    fun copy(u: User): User = u.copy(name = u.name + "$i", email = u.email)
                }
                """.trimIndent(),
            )
        }
        for (i in 0 until javaFiles) {
            write(
                src, "JavaUse$i.java",
                """
                package com.example.core;

                public class JavaUse$i {
                    private final String name = "j$i";
                    public String run(long id) {
                        User u = Repo.INSTANCE.load(id);
                        String formatted = ModelKt.formatUser(u);
                        int n = Helper.compute($i, 2);
                        return u.getName() + formatted + n + name + new Helper().name();
                    }
                    public String name() { return name; }
                }
                """.trimIndent(),
            )
        }
        return src
    }

    private fun awaitIndexed(ide: IdeServices) {
        val deadline = System.currentTimeMillis() + 600_000
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private inline fun <T> timed(label: String, results: MutableList<String>, block: () -> T): T {
        val start = System.nanoTime()
        val v = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        results += "%-44s %7d ms".format(label, ms)
        return v
    }

    private fun measure(ide: IdeServices, file: Path, needle: String, newName: String, what: String, results: MutableList<String>) {
        val text = Files.readString(file)
        val offset = text.indexOf(needle).also { check(it >= 0) { "$needle not in $file" } }
        timed("$what: prepareRename", results) { ide.prepareRename(file, text, offset) }
        val refs = timed("$what: findReferences (cold)", results) { ide.findReferences(file, text, offset) }
        timed("$what: findReferences (warm)", results) { ide.findReferences(file, text, offset) }
        results += "%-44s %7d refs".format("$what: references", refs.size)
        val outcome = timed("$what: rename", results) { runBlocking { ide.rename(file, text, offset, newName) } }
        results += "%-44s %s".format("$what: outcome", outcome.message)
        assertTrue(outcome.success, outcome.message)
        Thread.sleep(300) // let the mutation event start the re-index
        timed("$what: re-index after rename", results) { awaitIndexed(ide) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun overlaySize(ide: IdeServices): Int {
        val f = IdeServices::class.java.getDeclaredField("openDocuments").apply { isAccessible = true }
        return (f.get(ide) as Map<*, *>).size
    }

    /** What editing a file the renames never touched costs, and how many buffers the engine holds. */
    private fun editorLatency(ide: IdeServices, src: Path, phase: String, results: MutableList<String>) {
        results += "%-44s %7d".format("$phase: overlay buffers", overlaySize(ide))
        val file = src.resolve("Screen7.kt")
        val text = Files.readString(file)
        val at = text.indexOf("Repo.") + "Repo.".length
        repeat(3) { i ->
            timed("$phase: kotlin completion #$i", results) { runBlocking { ide.complete(file, text, at) } }
        }
        repeat(2) { i -> timed("$phase: kotlin analyze #$i", results) { ide.analyze(file, text) } }
        val jfile = src.resolve("JavaUse7.java")
        val jtext = Files.readString(jfile)
        val jat = jtext.indexOf("u.get") + "u.".length
        repeat(2) { i -> timed("$phase: java completion #$i", results) { runBlocking { ide.complete(jfile, jtext, jat) } } }
    }

    @Test
    fun timeFindUsagesAndRename() {
        assumeTrue(System.getProperty("refactor.bench") == "true", "opt-in: -Prefactor.bench=true")
        withTempDir("refactor-bench") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val src = generate(ide)
                awaitIndexed(ide)
                val results = ArrayList<String>()
                editorLatency(ide, src, "before renames", results)
                measure(ide, src.resolve("Model.kt"), "formatUser(u", "describeUser", "kotlin top-level fun", results)
                measure(ide, src.resolve("Model.kt"), "email:", "mail", "kotlin property", results)
                measure(ide, src.resolve("Model.kt"), "User(val", "Person", "kotlin class", results)
                measure(ide, src.resolve("Helper.java"), "compute(", "calculate", "java static method", results)
                measure(ide, src.resolve("Helper.java"), "Helper {", "Assistant", "java class", results)
                editorLatency(ide, src, "after renames", results)
                println("=== refactor benchmark (${kotlinFiles} kt + ${javaFiles} java files) ===")
                results.forEach(::println)
            }
        }
    }
}
