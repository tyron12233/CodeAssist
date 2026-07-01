package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `R` classes live only in the `R.jar` (external dex scope). [DexArchiveBuilderTask] must NOT dex an
 * `R.class`/`R$*.class` found in the *project* class output: a build that predated the `R.jar` path compiled
 * the whole gen tree (`R.java` + every `--extra-package` `R`) straight into the class dir, and those stale
 * `.class` are not cleaned when the compile switches to excluding `R.java`. Dexing them defines every `R` in
 * both the project scope (an earlier `classes*.dex`) and the `R.jar` scope (a later one); ART resolves the
 * first, so the stale project-scope `R` wins, and after a resource-id shift its constants no longer match the
 * packaged arsc — AppCompat's `R.styleable.AppCompatTheme` check then fails with "You need to use a
 * Theme.AppCompat theme (or descendant) with this activity."
 */
class DexArchiveProjectRExclusionTest {

    /** Mimics D8 `--file-per-class-file`: one `<class>.dex` per `.class` entry in the input jars. */
    private class PerClassDexer : Dexer {
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir)
            for (jar in inputs.filter { Files.exists(it) }) ZipFile(jar.toFile()).use { zf ->
                zf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                    val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                    dex.parent?.let { Files.createDirectories(it) }; Files.write(dex, byteArrayOf(1))
                }
            }
            return ToolResult.ok(emptyList())
        }
    }

    /** Emit a minimal, valid `.class` (the project scope runs it through ASM to strip Kotlin metadata). */
    private fun writeClass(root: Path, rel: String) {
        val internal = rel.removeSuffix(".class")
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internal, null, "java/lang/Object", null)
        cw.visitEnd()
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.write(f, cw.toByteArray())
    }

    @Test
    fun staleRClassesInProjectScopeAreNotDexed() = runBlocking {
        val tmp = Files.createTempDirectory("dex-r-exclude")
        try {
            val classes = tmp.resolve("classes")
            // A real app class (must be dexed) alongside stale generated R classes an old compile-R build left.
            writeClass(classes, "com/example/myproject/MainActivity.class")
            writeClass(classes, "com/example/myproject/R.class")
            writeClass(classes, "com/example/myproject/R\$styleable.class")
            writeClass(classes, "androidx/appcompat/R.class")
            writeClass(classes, "androidx/appcompat/R\$styleable.class")
            writeClass(classes, "com/google/android/material/R\$attr.class")
            // A user class whose name merely CONTAINS 'R' or has an unrelated nested class must NOT be excluded.
            writeClass(classes, "com/example/myproject/Repo.class")
            writeClass(classes, "com/example/myproject/Outer\$Inner.class")

            val projectDex = tmp.resolve("project-dex")
            val task = DexArchiveBuilderTask(
                TaskName(":app:dexBuilder"),
                projectClasses = listOf(classes), subProjectJars = emptyList(), externalJars = emptyList(),
                androidJar = tmp.resolve("android.jar"), minApi = 21, release = false,
                stagingJar = tmp.resolve("staging/project.jar"),
                projectDexRoot = projectDex, subDexRoot = tmp.resolve("sub"), extDexRoot = tmp.resolve("ext"),
                dexer = PerClassDexer(),
            )
            assertEquals(TaskResult.Success, task.execute(SimpleTaskContext()))

            fun dexed(rel: String) = Files.isRegularFile(projectDex.resolve(rel))
            // Real code is dexed as project scope.
            assertTrue(dexed("com/example/myproject/MainActivity.dex"), "app code must be dexed")
            assertTrue(dexed("com/example/myproject/Repo.dex"), "a class merely starting with 'R' is not an R class")
            assertTrue(dexed("com/example/myproject/Outer\$Inner.dex"), "an unrelated nested class is not an R class")
            // Generated R (own package + every extra-package) must NOT — it comes from the R.jar.
            assertFalse(dexed("com/example/myproject/R.dex"), "app R must not be dexed in the project scope")
            assertFalse(dexed("com/example/myproject/R\$styleable.dex"), "app R\$styleable must not be dexed in the project scope")
            assertFalse(dexed("androidx/appcompat/R.dex"), "extra-package R must not be dexed in the project scope")
            assertFalse(dexed("androidx/appcompat/R\$styleable.dex"), "extra-package R\$styleable must not be dexed in the project scope")
            assertFalse(dexed("com/google/android/material/R\$attr.dex"), "extra-package R\$attr must not be dexed in the project scope")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
