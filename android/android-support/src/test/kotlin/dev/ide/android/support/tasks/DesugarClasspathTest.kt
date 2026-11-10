package dev.ide.android.support.tasks

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each library is dexed against the rest of the app's libraries so D8 can desugar interface methods below
 * min-api 24. kotlin-stdlib, kotlinx-coroutines and most modern libraries are multi-release jars that all carry
 * `META-INF/versions/9/module-info.class`; counting that entry as a class made them "overlap" each other, so only
 * the first survived and a Compose build printed dozens of "Type `kotlin.coroutines.Continuation` was not found"
 * warnings (with the desugaring done blind).
 */
class DesugarClasspathTest {
    private val stdlib: Path = Paths.get("kotlin-stdlib.jar")
    private val coroutines: Path = Paths.get("kotlinx-coroutines-core.jar")
    private val serialization: Path = Paths.get("kotlinx-serialization-core.jar")
    private val shaded: Path = Paths.get("shaded-stdlib-copy.jar")

    private val classesOf = mapOf(
        stdlib to setOf("kotlin/coroutines/Continuation.class", "kotlin/jvm/functions/Function1.class", "META-INF/versions/9/module-info.class"),
        coroutines to setOf("kotlinx/coroutines/Job.class", "META-INF/versions/9/module-info.class"),
        serialization to setOf("kotlinx/serialization/KSerializer.class", "module-info.class", "kotlinx/serialization/package-info.class"),
        shaded to setOf("kotlin/coroutines/Continuation.class", "shaded/Extra.class"),
    )

    @Test
    fun multiReleaseJarsAllStayOnTheClasspath() {
        val cp = DexArchives.classpathFor(listOf(stdlib, coroutines, serialization), classesOf, exclude = emptySet())
        assertEquals(listOf(stdlib, coroutines, serialization), cp)
    }

    @Test
    fun aJarThatRedefinesAKeptTypeIsStillDropped() {
        val cp = DexArchives.classpathFor(listOf(stdlib, shaded, coroutines), classesOf, exclude = emptySet())
        assertEquals(listOf(stdlib, coroutines), cp)
    }

    @Test
    fun theProgramsOwnModuleInfoDoesNotEvictLibraries() {
        val cp = DexArchives.classpathFor(listOf(stdlib, coroutines), classesOf, exclude = setOf("META-INF/versions/9/module-info.class", "app/Main.class"))
        assertEquals(listOf(stdlib, coroutines), cp)
    }
}
