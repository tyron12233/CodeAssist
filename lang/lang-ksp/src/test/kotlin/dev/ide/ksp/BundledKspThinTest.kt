package dev.ide.ksp

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the `kspThinJar` build task bundled a real thin-KSP runner: the resource extracts to a jar that
 * contains KSP's own impl (`KotlinSymbolProcessing`) but NOT its stripped Analysis API — the ~776 KB artifact
 * `KspSourceGenerator` loads on top of our own compiler/AA.
 */
class BundledKspThinTest {

    @Test
    fun bundledThinRunnerExtractsAndContainsKspImplButNotAa() {
        assertTrue(BundledKspThin.isBundled(), "/ksp-thin.jar resource is missing — did the kspThinJar task run?")
        val jar = BundledKspThin.jar()
        assertNotNull(jar, "thin-KSP jar did not extract")
        assertTrue(Files.size(jar) > 0L, "thin-KSP jar is empty")

        ZipFile(jar.toFile()).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            assertTrue(
                names.any { it == "com/google/devtools/ksp/impl/KotlinSymbolProcessing.class" },
                "thin-KSP jar is missing KSP's impl (KotlinSymbolProcessing). Sample entries: ${names.take(10)}",
            )
            // The bundled Analysis API must be stripped (it comes from :kotlin-compiler-deps instead).
            assertTrue(
                names.none { it.startsWith("org/jetbrains/kotlin/analysis/") },
                "thin-KSP jar still bundles the Analysis API (org.jetbrains.kotlin.analysis.*) — it should be dropped",
            )
            // Sanity: it's small (KSP's own code only), not the 80 MB uber jar.
            assertTrue(names.size < 2000, "thin-KSP jar has ${names.size} entries — looks like the full uber jar, not thin")
        }
    }
}
