package dev.ide.android.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.ksp.BundledKspProcessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

/**
 * Every bundled processor's classpath, assembled on ART.
 *
 * The jars two or more closures resolved to the same artifact for ship once, in `/processors/shared.zip`,
 * instead of once inside each bundle. A bundle takes back only the jars it contributed, named in its own
 * `shared.list`; taking the whole pool would be wrong, because these closures disagree about versions by a
 * lot (guava is 30.1.1 in moshi, 33.2.1 in room and 33.6.0 in hilt), so a stray copy would land beside a
 * processor's own and let sort order decide which one it generates code against.
 *
 * That mechanism has a JVM test. This runs it where it actually ships: the zips come out of the APK rather
 * than a build directory, and the extraction lands under the app's `java.io.tmpdir`. It also covers the
 * bundle the split changed most. Room gave up both guava and kotlin-reflect and went from 12.6 MB to 5.8 MB,
 * and Room is the one processor with no ART test of its own - running it on device needs a bundled
 * `room-runtime` for `RoomDatabase` and is a separate follow-up - so without this, the heaviest-deduped
 * classpath was only ever checked off-device.
 */
@RunWith(AndroidJUnit4::class)
class BundledProcessorClasspathArtTest {

    private val ids = listOf("room", "room3", "moshi", "hilt", "glide")

    /** A bundle's extracted classpath is its own jars plus exactly the shared jars it claimed. */
    @Test
    fun everyBundleTakesOnlyTheSharedJarsItClaimed() {
        var checked = 0
        for (id in ids) {
            if (!BundledKspProcessors.isBundled(id)) continue
            checked++
            val own = mutableSetOf<String>()
            var claimed = emptySet<String>()
            ZipInputStream(javaClass.getResourceAsStream("/processors/$id.zip")!!).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    when {
                        e.name.endsWith(".jar") -> own += e.name.substringAfterLast('/')
                        e.name == "shared.list" -> claimed = zis.readBytes().decodeToString()
                            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            val actual = BundledKspProcessors.jarsFor(id).map { it.fileName.toString() }.toSet()
            assertEquals("$id's classpath on ART is not its own jars plus its claimed share", own + claimed, actual)
        }
        assertTrue("no processor bundle was found in the APK at all", checked > 0)
    }

    /**
     * Room's classpath carries the two jars it gave up, once each.
     *
     * Room packages neither guava nor kotlin-reflect any more, so if `shared.zip` stopped being built or
     * stopped being extracted its processor would fail at run time with a NoClassDefFoundError somewhere
     * inside code generation. A second copy would be just as wrong and quieter.
     */
    @Test
    fun roomGetsExactlyOneGuavaAndOneKotlinReflect() {
        if (!BundledKspProcessors.isBundled("room")) return
        val jars = BundledKspProcessors.jarsFor("room").map { it.fileName.toString() }
        for (library in listOf("guava-", "kotlin-reflect-")) {
            val found = jars.filter { it.startsWith(library) }
            assertEquals("room's ART classpath should carry exactly one $library* jar, got $found", 1, found.size)
        }
    }
}
