package dev.ide.core

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ensureKotlinStdlib` provisions the bundled kotlin-stdlib as the `kotlin-stdlib` library for every Kotlin
 * module. It used to create that library ONLY when absent and never re-verify it, so a since-deleted jar (a
 * cleared `.platform`, a version bump that renamed the file) left the persisted classpath entry dangling —
 * silently dropping the stdlib from the compile/dex classpaths (the runtime `NoClassDefFoundError:
 * kotlin/collections/CollectionsKt`). It must now re-extract and repoint a missing/stale entry.
 */
class KotlinStdlibProvisionHealTest {

    @Test
    fun reExtractsAndRepointsADanglingKotlinStdlib() {
        val dir = Files.createTempDirectory("stdlib-heal")
        IdeServices.bootstrapDemo(dir).use { ide ->
            // Make the app module Kotlin so the bundled stdlib is provisioned (the demo is Java-only otherwise).
            val ktRoot = dir.resolve("app/src/main/kotlin/com/example/app")
            Files.createDirectories(ktRoot)
            Files.writeString(ktRoot.resolve("K.kt"), "package com.example.app\nfun k() {}\n")

            ide.ensureKotlinStdlib()
            val lib = ide.store.workspace.libraryTable.byName("kotlin-stdlib")
            assertNotNull(lib, "kotlin-stdlib should be provisioned for a Kotlin module")
            val jar = Paths.get(lib.classesRoots.single().path)
            assertTrue(Files.exists(jar), "the provisioned stdlib jar should exist on disk: $jar")

            // Only exercise the destructive heal when the jar is the workspace-local extracted copy — never
            // delete a shared host jar (the fallback used when the bundled resource isn't on the test classpath).
            if (!jar.startsWith(dir)) return@use
            Files.delete(jar)
            assertTrue(!Files.exists(jar), "stdlib jar deleted to simulate a cleared .platform")

            // The next ensure must notice the dangling entry, re-extract the jar, and keep the library pointing
            // at a jar that exists.
            ide.ensureKotlinStdlib()
            val healed = Paths.get(ide.store.workspace.libraryTable.byName("kotlin-stdlib")!!.classesRoots.single().path)
            assertTrue(Files.exists(healed), "a deleted stdlib jar must be re-extracted on the next ensureKotlinStdlib: $healed")
        }
        dir.toFile().deleteRecursively()
    }
}
