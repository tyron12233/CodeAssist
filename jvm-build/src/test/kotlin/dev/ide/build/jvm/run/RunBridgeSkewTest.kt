package dev.ide.build.jvm.run

import dev.ide.jvm.VmUnsupportedException
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The explanation a console run gives when a bridged class is missing the member the program was compiled
 * against.
 *
 * The reported failure was `no static kotlinx/coroutines/BuildersKt.runBlockingK$default(...)` and nothing in
 * it said why: `kotlin/` and `kotlinx/` are bridged, so the run executes the copy the IDE ships rather than
 * the project's, and the project had a newer kotlinx-coroutines whose `runBlocking` sits behind a `@JvmName`
 * the IDE's copy never had. The reporter had to bisect library versions to work that out.
 */
class RunBridgeSkewTest {

    @Test fun aMissingMemberOnAProjectSuppliedBridgedClassNamesBothCopies() {
        withTempDir("run-bridge-skew") { tmp ->
            val jar = libraryJar(tmp.resolve("coroutines-9.9.9.jar"), version = "9.9.9")
            val bridge = RunBridge(javaClass.classLoader, ProgramWindows(), listOf(jar))

            val e = assertFailsWith<VmUnsupportedException> {
                bridge.invokeStatic("kotlinx/coroutines/BuildersKt", "runBlockingK\$default", "()V", emptyList())
            }

            val message = e.message.orEmpty()
            assertTrue("runBlockingK" in message, "the original failure survives: $message")
            assertTrue("kotlinx-coroutines-core 9.9.9" in message, "name the project's copy: $message")
            assertTrue("CodeAssist ships" in message, "and say which copy actually runs: $message")
            hostCoroutinesVersion()?.let {
                assertTrue("($it)" in message, "the IDE's own version is worth naming when known: $message")
            }
        }
    }

    @Test fun thesameVersionOnBothSidesIsNotReportedAsSkew() {
        // The member is then absent everywhere, so blaming the library version would send the user off to
        // change something that is already right.
        val host = hostCoroutinesVersion() ?: return // no manifest to compare against (a dexed/unpacked host)
        withTempDir("run-bridge-same") { tmp ->
            val jar = libraryJar(tmp.resolve("coroutines-$host.jar"), version = host)
            val bridge = RunBridge(javaClass.classLoader, ProgramWindows(), listOf(jar))

            val e = assertFailsWith<VmUnsupportedException> {
                bridge.invokeStatic("kotlinx/coroutines/BuildersKt", "notAMethod", "()V", emptyList())
            }

            assertTrue("CodeAssist ships" !in e.message.orEmpty(), "nothing to explain: ${e.message}")
        }
    }

    @Test fun aPlatformClassFailureIsLeftAlone() {
        withTempDir("run-bridge-platform") { tmp ->
            val jar = libraryJar(tmp.resolve("coroutines-9.9.9.jar"), version = "9.9.9")
            val bridge = RunBridge(javaClass.classLoader, ProgramWindows(), listOf(jar))

            // java.lang.System is nothing the project ships, so there is no second copy to blame.
            val e = assertFailsWith<VmUnsupportedException> {
                bridge.invokeStatic("java/lang/System", "notAMethod", "()V", emptyList())
            }

            assertTrue("CodeAssist ships" !in e.message.orEmpty(), "nothing to explain: ${e.message}")
        }
    }

    /** A stand-in for the project's dependency: a jar that CLAIMS `kotlinx/coroutines/BuildersKt` at [version].
     *  Only the entry name and the manifest matter here; nothing is ever loaded from it. */
    private fun libraryJar(path: Path, version: String): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_TITLE] = "kotlinx-coroutines-core"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = version
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { out ->
            out.putNextEntry(ZipEntry("kotlinx/coroutines/BuildersKt.class"))
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            out.closeEntry()
        }
        return path
    }

    /** The version the IDE's own kotlinx-coroutines declares, or null when it is not running from a jar. */
    private fun hostCoroutinesVersion(): String? =
        runCatching { Class.forName("kotlinx.coroutines.BuildersKt") }.getOrNull()?.`package`?.implementationVersion
}
