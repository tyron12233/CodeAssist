package dev.ide.core

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Package-private SDK types must not be indexed as suggestible class names; public siblings still are. */
class TypeVisibilityE2ETest {

    @Test
    fun packagePrivateSdkClassIsNotSuggested() {
        val dir = Files.createTempDirectory("ide-vis")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val id = IndexId("java.classNames")
            // wait until the public java.util.Arrays has been indexed
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline &&
                ide.indexService.fuzzy<ClassNameValue>(id, "Arrays", 50).none { it.value.fqn == "java.util.Arrays" }) Thread.sleep(100)
            Thread.sleep(500)

            val fqns = ide.indexService.fuzzy<ClassNameValue>(id, "Arrays", 50).map { it.value.fqn }.toList()
            assertTrue("java.util.Arrays" in fqns, "public java.util.Arrays must be indexed: $fqns")
            assertFalse(
                "java.util.ArraysParallelSortHelpers" in fqns,
                "package-private java.util.ArraysParallelSortHelpers must be filtered out: $fqns",
            )
        }
        dir.toFile().deleteRecursively()
    }
}
