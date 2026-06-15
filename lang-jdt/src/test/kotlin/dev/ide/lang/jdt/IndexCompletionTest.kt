package dev.ide.lang.jdt

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies the index is consulted as a candidate source by context: unimported types, package paths, imports. */
class IndexCompletionTest {

    /** Data-driven fake: classNames (fuzzy by simple-name prefix), packageTypes (exact by package), packages (prefix). */
    private class FakeIndex(
        val classNames: List<ClassNameValue> = emptyList(),
        val packageTypes: Map<String, List<ClassNameValue>> = emptyMap(),
        val packages: List<String> = emptyList(),
    ) : IndexService {
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (id.value == "java.packageTypes") (packageTypes[key] ?: emptyList()).asSequence().map { it as V } else emptySequence()

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
            if (id.value == "java.packages") packages.filter { it.startsWith(prefix) }.asSequence().map { Hit(it, it as V, 700) } else emptySequence()

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
            if (id.value == "java.classNames") {
                classNames.filter { it.fqn.substringAfterLast('.').startsWith(pattern, ignoreCase = true) }
                    .asSequence().map { Hit(it.fqn.substringAfterLast('.'), it as V, 800) }
            } else emptySequence()

        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus()
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable {}
    }

    @Test
    fun offersUnimportedClassWithAutoImport() {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(classNames = listOf(ClassNameValue("java.util.List", IndexOrigin.SDK, "interface")))
        try {
            val result = completeResult(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { Li|CARET| } }")
            val item = result.items.firstOrNull { it.insertText == "List" }
            assertNotNull(item, "expected unimported 'List': ${result.items.map { it.label }}")
            val edit = item.additionalEdits.firstOrNull()
            assertNotNull(edit, "expected auto-import edit")
            assertTrue(edit.newText.contains("import java.util.List;"), "import text: ${edit.newText}")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun noImportEditForSamePackageType() {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(classNames = listOf(ClassNameValue("app.Helper", IndexOrigin.SOURCE, "class")))
        try {
            val result = completeResult(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { Hel|CARET| } }")
            val item = result.items.firstOrNull { it.insertText == "Helper" }
            assertNotNull(item)
            assertTrue(item.additionalEdits.isEmpty(), "same-package type should not add an import")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun listsTypesAndSubPackagesUnderAPackagePath() {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(
            packageTypes = mapOf("java.util" to listOf(
                ClassNameValue("java.util.List", IndexOrigin.SDK, "interface"),
                ClassNameValue("java.util.ArrayList", IndexOrigin.SDK, "class"),
            )),
            packages = listOf("java.util", "java.util.concurrent"),
        )
        try {
            val labels = completeLabels(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { java.util.|CARET| } }")
            assertTrue(labels.containsAll(listOf("List", "ArrayList")), "types under java.util: $labels")
            assertTrue("concurrent" in labels, "sub-package under java.util: $labels")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun fullyQualifiedTypeIsInsertedWithoutImport() {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(packageTypes = mapOf("java.util" to listOf(ClassNameValue("java.util.List", IndexOrigin.SDK, "interface"))))
        try {
            val result = completeResult(analyzer, dir.resolve("app/T.java"), "package app; class T { void m() { java.util.Li|CARET| } }")
            val item = result.items.firstOrNull { it.insertText == "List" }
            assertNotNull(item, "expected 'List' under java.util: ${result.items.map { it.label }}")
            assertEquals(CompletionItemKind.INTERFACE, item.kind)
            assertTrue(item.additionalEdits.isEmpty(), "FQ name should NOT add an import")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun completesPackagesInsideImportStatement() {
        val (analyzer, dir) = workspaceWith()
        analyzer.indexService = FakeIndex(packages = listOf("java", "java.util", "java.io"))
        try {
            val labels = completeLabels(analyzer, dir.resolve("app/T.java"), "package app;\nimport java.u|CARET|\nclass T {}")
            assertTrue("util" in labels, "import-position package completion: $labels")
        } finally { dir.toFile().deleteRecursively() }
    }
}
