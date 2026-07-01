package dev.ide.android.support.tools

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AAR `aar-metadata.properties` reader + `minCompileSdk` gate (AGP's `CheckAarMetadataTask` behaviour):
 * a dependency requiring a higher compileSdk than the app is an error; anything lower/equal/absent is fine.
 */
class AarMetadataTest {

    @Test
    fun readsMinCompileSdkFromPropertiesFile() {
        val dir = Files.createTempDirectory("aarmeta")
        val f = dir.resolve("aar-metadata.properties")
        f.writeText(
            """
            aarFormatVersion=1.0
            aarMetadataVersion=1.0
            minCompileSdk=34
            minCompileSdkExtension=0
            minAndroidGradlePluginVersion=7.2.0
            """.trimIndent()
        )
        val info = AarMetadata.read(f)
        assertEquals("34", info.minCompileSdk)
        assertEquals("7.2.0", info.minAgpVersion)
        assertTrue(!info.isEmpty)
    }

    @Test
    fun missingFileIsEmpty() {
        val absent = Files.createTempDirectory("aarmeta").resolve("nope.properties")
        assertTrue(AarMetadata.read(absent).isEmpty, "a missing metadata file must impose no constraint")
    }

    @Test
    fun higherMinCompileSdkIsAnError() {
        // Library needs API 34, app compiles against 33 -> error naming both versions and the dependency.
        val errs = AarMetadata.check(compileSdk = 33, name = "androidx.core-1.13.0.aar", info = AarMetadata.Info(minCompileSdk = "34"))
        assertEquals(1, errs.size, "expected one error: $errs")
        assertTrue("version 34 or later" in errs[0], errs[0])
        assertTrue("android-33" in errs[0], errs[0])
        assertTrue("androidx.core-1.13.0.aar" in errs[0], errs[0])
    }

    @Test
    fun equalOrLowerMinCompileSdkIsFine() {
        assertTrue(AarMetadata.check(36, "lib", AarMetadata.Info(minCompileSdk = "36")).isEmpty())
        assertTrue(AarMetadata.check(36, "lib", AarMetadata.Info(minCompileSdk = "30")).isEmpty())
    }

    @Test
    fun absentMinCompileSdkImposesNoFloor() {
        // An older/non-AGP AAR (no metadata, or metadata without minCompileSdk) never fails the gate.
        assertTrue(AarMetadata.check(21, "old-lib", AarMetadata.Info()).isEmpty())
        assertTrue(AarMetadata.check(21, "old-lib", AarMetadata.Info(minAgpVersion = "3.0.0")).isEmpty())
    }

    @Test
    fun nonIntegerMinCompileSdkIsReportedNotCrashed() {
        val errs = AarMetadata.check(30, "weird", AarMetadata.Info(minCompileSdk = "Tiramisu"))
        assertEquals(1, errs.size)
        assertTrue("invalid minCompileSdk value (Tiramisu)" in errs[0], errs[0])
    }
}
