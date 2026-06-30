package dev.ide.core

import dev.ide.ui.backend.UiKeystoreSpec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end through the engine's signing service: create a keystore in the shared registry, list it, assign
 * it to the app's `release` build type, and read the assignment back (which proves it persisted on the
 * facet). A non-Android module reports no signing. SDK-independent (no build runs). Bouncy Castle is a test
 * dependency so the in-process create works on the desktop JVM.
 */
class KeystoreSigningTest {

    @Test
    fun `create, assign to release, and read back`() {
        val dir = Files.createTempDirectory("ide-keystore-signing")
        IdeServices.bootstrapDemo(dir, sharedCachesRoot = dir).use { ide ->
            val created = ide.signing.createKeystore(
                UiKeystoreSpec(name = "release", storePass = "android123", keyAlias = "upload", commonName = "Acme"),
            )
            assertTrue(created.success, created.message)
            val id = assertNotNull(created.keystoreId)
            assertTrue(ide.signing.keystores().any { it.id == id }, "the created keystore is listed")

            // The app's release build type starts on the debug default (no signingConfig).
            val before = assertNotNull(ide.signing.signingAssignments("app"))
            assertNull(before.assignments.first { it.buildType == "release" }.keystoreId)

            val result = ide.signing.assignSigning("app", "release", id)
            assertTrue(result.success, result.message)

            val after = assertNotNull(ide.signing.signingAssignments("app"))
            assertEquals(id, after.assignments.first { it.buildType == "release" }.keystoreId)
            assertNull(after.assignments.first { it.buildType == "debug" }.keystoreId, "debug stays on the default")

            // Clearing the assignment falls back to the default.
            assertTrue(ide.signing.assignSigning("app", "release", null).success)
            assertNull(assertNotNull(ide.signing.signingAssignments("app")).assignments.first { it.buildType == "release" }.keystoreId)

            // A plain java-lib module has no Android signing.
            assertNull(ide.signing.signingAssignments("core"))

            // signableModules drives the Keystore Manager's "jump to signing" — only the android-app.
            assertEquals(listOf("app"), ide.signing.signableModules())
        }
        dir.toFile().deleteRecursively()
    }
}
