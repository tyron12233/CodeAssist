package dev.ide.android.support.tools

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-home keystore registry: create/import/list/delete + a build [SigningConfig] resolved from a stored
 * id, all persisted across instances via `registry.json`.
 */
class KeystoreRegistryTest {

    @Test
    fun `create, resolve, persist, import and delete`() {
        val dir = createTempDirectory("ks-reg")
        try {
            val reg = KeystoreRegistry(dir)
            val created = reg.create("My Release Key", KeystoreCreateSpec("storepass", "upload", "Acme"))
            assertTrue(created.isSuccess, created.exceptionOrNull()?.message)
            val entry = created.getOrThrow()
            assertEquals("my-release-key", entry.id, "id is a slug of the name")
            assertEquals(listOf(entry.id), reg.all().map { it.id })

            // signingConfigFor maps the id → the build's SigningConfig (PKCS12: one password for store + key).
            val cfg = reg.signingConfigFor(entry.id)!!
            assertEquals("upload", cfg.keyAlias)
            assertEquals("storepass", cfg.storePass)
            assertEquals("storepass", cfg.keyPass)
            assertTrue(Files.isRegularFile(cfg.keystore))
            assertNull(reg.signingConfigFor("does-not-exist"))

            // A fresh registry over the same dir reads the persisted entry back (registry.json round-trip).
            assertEquals(listOf(entry.id), KeystoreRegistry(dir).all().map { it.id })

            // Import the same file under a different name (its store password verifies).
            val imported = reg.import("Backup", cfg.keystore, "storepass", "upload", "storepass")
            assertTrue(imported.isSuccess, imported.exceptionOrNull()?.message)
            assertEquals(2, reg.all().size)

            assertTrue(reg.delete(entry.id))
            assertEquals(setOf("backup"), reg.all().map { it.id }.toSet())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `import rejects a wrong password`() {
        val dir = createTempDirectory("ks-reg-bad")
        try {
            val reg = KeystoreRegistry(dir)
            val entry = reg.create("k", KeystoreCreateSpec("rightpw", "a0", "CN")).getOrThrow()
            val cfg = reg.signingConfigFor(entry.id)!!
            assertTrue(reg.import("bad", cfg.keystore, "wrong-password", "a0", "wrong-password").isFailure)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
