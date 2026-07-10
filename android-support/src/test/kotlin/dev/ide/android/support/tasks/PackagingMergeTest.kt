package dev.ide.android.support.tasks

import dev.ide.android.support.JniLibsPackaging
import dev.ide.android.support.ResourcePackaging
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the AGP-faithful packaging merge: the glob matcher, the Java-resource merge (default
 * excludes, `META-INF/services` concatenation, first-wins), and the native-lib merge (project + jar `.so`,
 * dedup, excludes). No Android SDK needed — pure file/zip logic.
 */
class PackagingMergeTest {

    // --- glob matching ---

    @Test
    fun globMatchesLikeAgp() {
        fun m(pattern: String, path: String) = PackagingRules.globToRegex(pattern).matches(path)

        // leading slash is optional; * stays within a segment
        assertTrue(m("/META-INF/MANIFEST.MF", "META-INF/MANIFEST.MF"))
        assertTrue(m("/META-INF/*.kotlin_module", "META-INF/foo.kotlin_module"))
        assertFalse(m("/META-INF/*.kotlin_module", "META-INF/sub/foo.kotlin_module")) // * doesn't cross '/'
        // ** crosses '/'
        assertTrue(m("/META-INF/maven/**", "META-INF/maven/g/a/pom.xml"))
        // **/ prefix matches zero-or-more leading segments (Ant semantics)
        assertTrue(m("**/*.kotlin_metadata", "x.kotlin_metadata"))
        assertTrue(m("**/*.kotlin_metadata", "a/b/x.kotlin_metadata"))
        // hidden-file default
        assertTrue(m("/**/.*", "a/b/.DS_Store"))
        assertTrue(m("/**/.*", ".gitkeep"))
        // ? is a single non-separator
        assertTrue(m("lib/x86/lib?.so", "lib/x86/liba.so"))
        assertFalse(m("lib/x86/lib?.so", "lib/x86/lib/.so"))
    }

    @Test
    fun filterPrecedenceExcludeWins() {
        val f = PackagingRules.Filter(
            excludes = listOf("/META-INF/services/skip"),
            pickFirsts = listOf("**/first.txt"),
            merges = listOf("/META-INF/services/**"),
        )
        assertEquals(PackagingRules.Action.EXCLUDE, f.actionFor("META-INF/services/skip")) // exclude beats merge
        assertEquals(PackagingRules.Action.MERGE, f.actionFor("META-INF/services/com.x.Svc"))
        assertEquals(PackagingRules.Action.PICK_FIRST, f.actionFor("a/first.txt"))
        assertEquals(PackagingRules.Action.DEFAULT, f.actionFor("plain.txt"))
    }

    // --- Java resources ---

    @Test
    fun javaResMergeAppliesDefaultsAndMergesServices() {
        val dir = Files.createTempDirectory("javares")
        try {
            // A "project" resources dir and two jars each registering a service + carrying manifest noise.
            val projRes = dir.resolve("proj-res")
            write(projRes.resolve("app.properties"), "from-app")
            write(projRes.resolve("META-INF/services/com.x.Svc"), "com.x.AppImpl")

            val jarA = makeJar(dir.resolve("a.jar"), mapOf(
                "com/x/A.class" to "cafebabe",                    // dexed, not a resource
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0", // default-excluded
                "META-INF/foo.kotlin_module" to "km",              // default-excluded
                "META-INF/services/com.x.Svc" to "com.x.AImpl",    // merged
                "lib.properties" to "from-a",
            ))
            val jarB = makeJar(dir.resolve("b.jar"), mapOf(
                "META-INF/services/com.x.Svc" to "com.x.BImpl",    // merged
                "lib.properties" to "from-b-should-lose",          // duplicate → first (jarA) wins
            ))

            val out = dir.resolve("merged.jar")
            val filter = PackagingRules.resourceFilter(ResourcePackaging())
            JavaResMerger.merge(listOf(projRes), listOf(jarA, jarB), filter, out)

            val entries = zipEntries(out)
            // Default excludes dropped the manifest + kotlin_module; no .class landed as a resource.
            assertFalse("META-INF/MANIFEST.MF" in entries.keys, "MANIFEST.MF should be excluded")
            assertFalse("META-INF/foo.kotlin_module" in entries.keys, "kotlin_module should be excluded")
            assertFalse(entries.keys.any { it.endsWith(".class") }, "no .class as resource")
            // Project resource + non-duplicate lib resource packaged.
            assertEquals("from-app", entries["app.properties"])
            // Duplicate: project offered first? No — first provider is projRes (no lib.properties), then jarA wins.
            assertEquals("from-a", entries["lib.properties"])
            // Services concatenated across project + both jars, newline-separated, project first.
            val svc = entries["META-INF/services/com.x.Svc"]!!.trim().lines().toSet()
            assertEquals(setOf("com.x.AppImpl", "com.x.AImpl", "com.x.BImpl"), svc)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun javaResPickFirstKeepsProjectCopy() {
        val dir = Files.createTempDirectory("javares-pick")
        try {
            val projRes = dir.resolve("proj"); write(projRes.resolve("conf/app.cfg"), "project-wins")
            val jar = makeJar(dir.resolve("dep.jar"), mapOf("conf/app.cfg" to "dep-loses"))
            val filter = PackagingRules.resourceFilter(ResourcePackaging(pickFirsts = setOf("**/app.cfg")))
            val out = dir.resolve("m.jar")
            JavaResMerger.merge(listOf(projRes), listOf(jar), filter, out)
            assertEquals("project-wins", zipEntries(out)["conf/app.cfg"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun javaResMergeWritesReadableEmptyJarWhenNothingToPackage() {
        val dir = Files.createTempDirectory("javares-empty")
        try {
            // No resource dirs, and a jar with only a class → nothing to package.
            val jar = makeJar(dir.resolve("classes.jar"), mapOf("com/x/A.class" to "cafebabe"))
            val out = dir.resolve("empty.jar")
            val n = JavaResMerger.merge(emptyList(), listOf(jar), PackagingRules.resourceFilter(ResourcePackaging()), out)
            assertEquals(0, n)
            // The output must exist and be openable (a zero-entry ZipOutputStream would throw "No entries" on ART).
            assertTrue(Files.isRegularFile(out))
            assertEquals(0, zipEntries(out).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- native libs ---

    @Test
    fun nativeLibsMergeCollectsDirsAndJarsWithDedup() {
        val dir = Files.createTempDirectory("jni")
        try {
            // project jniLibs dir laid out <abi>/name.so
            val jniDir = dir.resolve("jniLibs")
            write(jniDir.resolve("x86/libapp.so"), "app-x86")
            write(jniDir.resolve("arm64-v8a/libapp.so"), "app-arm")
            // a second dir contributing a duplicate (lower priority → dropped)
            val jniDir2 = dir.resolve("aar-jni")
            write(jniDir2.resolve("x86/libapp.so"), "aar-x86-should-lose")
            write(jniDir2.resolve("x86/libaar.so"), "aar-only")
            // a jar carrying lib/<abi>/*.so
            val jar = makeJar(dir.resolve("native.jar"), mapOf(
                "lib/arm64-v8a/libjar.so" to "jar-arm",
                "com/x/A.class" to "cafebabe",     // not a .so
                "notlib/x86/libskip.so" to "skip", // not under lib/
            ))

            val out = dir.resolve("merged")
            val filter = PackagingRules.jniLibsFilter(JniLibsPackaging())
            NativeLibsMerger.merge(listOf(jniDir, jniDir2), listOf(jar), filter, out)

            assertEquals("app-x86", read(out.resolve("x86/libapp.so")))   // first dir wins the dup
            assertEquals("app-arm", read(out.resolve("arm64-v8a/libapp.so")))
            assertEquals("aar-only", read(out.resolve("x86/libaar.so")))
            assertEquals("jar-arm", read(out.resolve("arm64-v8a/libjar.so")))
            assertFalse(Files.exists(out.resolve("x86/libskip.so")), "non-lib/ jar entry must be ignored")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeLibsExcludeDropsMatch() {
        val dir = Files.createTempDirectory("jni-excl")
        try {
            val jniDir = dir.resolve("jniLibs")
            write(jniDir.resolve("x86/libkeep.so"), "keep")
            write(jniDir.resolve("x86/libc++_shared.so"), "drop")
            val filter = PackagingRules.jniLibsFilter(JniLibsPackaging(excludes = setOf("**/libc++_shared.so")))
            val out = dir.resolve("merged")
            NativeLibsMerger.merge(listOf(jniDir), emptyList(), filter, out)
            assertTrue(Files.exists(out.resolve("x86/libkeep.so")))
            assertFalse(Files.exists(out.resolve("x86/libc++_shared.so")), "excluded .so must not be packaged")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- helpers ---

    private fun write(p: Path, content: String) {
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    private fun read(p: Path): String = Files.readString(p)

    private fun makeJar(jar: Path, entries: Map<String, String>): Path {
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return jar
    }

    private fun zipEntries(jar: Path): Map<String, String> = ZipFile(jar.toFile()).use { zf ->
        zf.entries().asSequence().filter { !it.isDirectory }
            .associate { it.name to zf.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }
}
