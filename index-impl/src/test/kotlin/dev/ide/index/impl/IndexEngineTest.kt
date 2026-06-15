package dev.ide.index.impl

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

private val CLASS = IndexId("test.classNames")

/** Exercises the generic engine: JDK enumeration + JPMS visibility filter, fuzzy, and cache reuse. */
class IndexEngineTest {

    /** A trivial class-name index over class-file entries (mirrors what the JDT backend does). */
    private object TestClassIndex : IndexExtension<String, ClassNameValue> {
        override val id = CLASS
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<ClassNameValue> = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { it.unitName?.endsWith(".class") == true }
        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val (fqn, simple) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
    }

    private fun service(cache: Path) = IndexServiceImpl(listOf(TestClassIndex), cache)
    private fun jdk() = Path.of(System.getProperty("java.home"))

    @Test
    fun indexesAccessibleJdkTypesAndExcludesInternals() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val list = svc.prefix<ClassNameValue>(CLASS, "List", 200).map { it.value.fqn }.toList()
            assertTrue("java.util.List" in list, "expected java.util.List, got ${list.take(10)}")
            // JPMS visibility: jdk.internal.* is never exported, so it must not be indexed.
            val internals = svc.prefix<ClassNameValue>(CLASS, "L", 5000).count { it.value.fqn.startsWith("jdk.internal.") }
            assertTrue(internals == 0, "jdk.internal.* leaked into the index ($internals hits)")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun fuzzyFindsArrayList() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val hits = svc.fuzzy<ClassNameValue>(CLASS, "rray", 200).map { it.value.fqn }.toList()
            assertTrue(hits.any { it.endsWith(".ArrayList") }, "fuzzy 'rray' should surface ArrayList: ${hits.take(10)}")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidateClearsBuiltDataAndAllowsRebuild() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).any { it.value.fqn == "java.util.List" })

            runBlocking { svc.invalidate() }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).none(), "invalidate must drop all built data")
            assertTrue(Files.list(cache).use { it.findAny().isEmpty }, "invalidate must clear the on-disk cache")

            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).any { it.value.fqn == "java.util.List" }, "rebuild after invalidate")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun reusesPersistedCacheAcrossInstances() {
        val cache = Files.createTempDirectory("idx")
        try {
            runBlocking { service(cache).ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val svc2 = service(cache)
            runBlocking { svc2.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc2.prefix<ClassNameValue>(CLASS, "List", 200).any { it.value.fqn == "java.util.List" })
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
