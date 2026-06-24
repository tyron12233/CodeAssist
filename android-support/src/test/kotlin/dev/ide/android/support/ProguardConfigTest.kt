package dev.ide.android.support

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProguardConfigTest {

    @Test
    fun extractsBundledDefaultsFromAssets() {
        val dir = Files.createTempDirectory("pg-defaults")
        try {
            val opt = DefaultProguardFiles.extract(DefaultProguardFiles.OPTIMIZE, dir)
            assertTrue(opt != null && Files.isRegularFile(opt), "optimize default must extract")
            val text = Files.readAllBytes(opt!!).decodeToString()
            assertTrue("-optimizationpasses" in text, "the optimize variant carries optimization directives")
            assertTrue("-keepclassmembers enum *" in text, "standard enum keep rule present")

            val base = DefaultProguardFiles.extract(DefaultProguardFiles.DEFAULT, dir)
            assertTrue(base != null && "-dontoptimize" in Files.readAllBytes(base!!).decodeToString(),
                "the non-optimize variant disables optimization")
            assertNull(DefaultProguardFiles.extract("not-a-default.txt", dir), "unknown names are not defaults")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolvesDefaultsAndModuleRelativeFilesAndSkipsMissing() {
        val module = Files.createTempDirectory("pg-module")
        val defaults = module.resolve("build/defaults")
        try {
            Files.writeString(module.resolve("proguard-rules.pro"), "-keep class com.example.Kept { *; }")

            val resolved = resolveProguardFiles(
                listOf(DefaultProguardFiles.OPTIMIZE, "proguard-rules.pro", "does-not-exist.pro"),
                module, defaults,
            )
            // The default extracts, the present module file resolves, the missing one is dropped.
            assertEquals(2, resolved.size, "missing module-relative files are skipped")
            assertEquals(DefaultProguardFiles.OPTIMIZE, resolved[0].fileName.toString())
            assertEquals("proguard-rules.pro", resolved[1].fileName.toString())
            assertTrue(resolved.all { Files.isRegularFile(it) })
        } finally {
            module.toFile().deleteRecursively()
        }
    }
}
