package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Keystore Manager creates a signing keystore: BouncyCastle/JCA providers looked up by algorithm name. */
@RunWith(AndroidJUnit4::class)
class KeystoreTest : E2eTest() {

    @Test
    fun createsAKeystore() {
        openSettings()
        clickObject(scrollTo("Keystore Manager"))
        await("Signing keystores")
        click("Create")
        await("New Keystore")
        val name = uniqueName("e2e")
        fill("Name", name)
        fill("Key alias", "e2ekey")
        fill("Password (", "e2e-secret")
        fill("Full name (CN)", "E2E Bot")
        click("Create keystore")
        // Back on the manager, the new store's card reads "<file> · key: e2ekey".
        awaitTextContains("key: e2ekey", 60_000)
        await(name)
    }
}
