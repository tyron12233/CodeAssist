package dev.ide.vcs.impl

import dev.ide.testkit.withTempDir
import dev.ide.vcs.VcsAccount
import dev.ide.vcs.VcsCredentials
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account store: what it lists, what it hands a transport, and what it leaves on disk. */
class FileAccountStoreTest {

    private fun account(login: String, host: String = "api.github.com") = VcsAccount(
        id = VcsAccount.idOf(VcsAccount.FORGE_GITHUB, host, login),
        forgeId = VcsAccount.FORGE_GITHUB,
        host = host,
        login = login,
        name = login.replaceFirstChar { it.uppercase() },
    )

    @Test
    fun `an added account is listed, active, and gives back its token`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            val stored = store.add(account("octocat"), "gho_secret")

            assertEquals(listOf("octocat"), store.accounts().map { it.login })
            assertEquals(stored.id, store.activeAccount()?.id)
            assertEquals("gho_secret", store.token(stored.id))
            assertTrue(stored.addedMs > 0L, "the store stamps when the account was added")
        }
    }

    @Test
    fun `a second account does not steal the active slot until it is chosen`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            val first = store.add(account("first"), "t1")
            val second = store.add(account("second"), "t2")

            assertEquals(first.id, store.activeAccount()?.id)
            store.setActive(second.id)
            assertEquals(second.id, store.activeAccount()?.id)
        }
    }

    @Test
    fun `removing the active account promotes the remaining one`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            val first = store.add(account("first"), "t1")
            store.add(account("second"), "t2")

            store.remove(first.id)
            assertEquals(listOf("second"), store.accounts().map { it.login })
            assertEquals("second", store.activeAccount()?.login)
            assertNull(store.token(first.id))
        }
    }

    @Test
    fun `accounts survive a reopen of the store`() {
        withTempDir("vcs-accounts") { dir ->
            FileAccountStore(dir).add(account("octocat"), "gho_secret")

            val reopened = FileAccountStore(dir)
            val loaded = assertNotNull(reopened.accounts().singleOrNull())
            assertEquals("octocat", loaded.login)
            assertEquals("Octocat", loaded.name)
            assertEquals("gho_secret", reopened.token(loaded.id))
        }
    }

    @Test
    fun `a github account authenticates a github com remote`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            store.add(account("octocat"), "gho_secret")

            val credentials = store.credentialsFor("https://github.com/octocat/hello.git")
            val token = assertNotNull(credentials as? VcsCredentials.Token)
            assertEquals("gho_secret", token.token)
            assertEquals("octocat", token.username)
        }
    }

    @Test
    fun `an unrelated host falls back to anonymous`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            store.add(account("octocat"), "gho_secret")

            assertEquals(VcsCredentials.Anonymous, store.credentialsFor("https://gitlab.com/x/y.git"))
        }
    }

    @Test
    fun `saved host credentials are used for a server with no account`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            store.saveHostCredentials("git.example.com", "ada", "hunter2")

            assertEquals(listOf("git.example.com"), store.credentialHosts())
            val credentials = store.credentialsFor("https://git.example.com/team/app.git")
            val pair = assertNotNull(credentials as? VcsCredentials.UserPassword)
            assertEquals("ada", pair.username)
            assertEquals("hunter2", pair.password)

            store.clearHostCredentials("git.example.com")
            assertTrue(store.credentialHosts().isEmpty())
            assertEquals(VcsCredentials.Anonymous, store.credentialsFor("https://git.example.com/team/app.git"))
        }
    }

    @Test
    fun `secrets are not readable in the files on disk`() {
        withTempDir("vcs-accounts") { dir ->
            val store = FileAccountStore(dir)
            store.add(account("octocat"), "gho_super_secret")
            store.saveHostCredentials("git.example.com", "ada", "hunter2")

            val onDisk = Files.list(dir).use { paths ->
                paths.toList().joinToString("\n") { runCatching { Files.readString(it) }.getOrDefault("") }
            }
            assertFalse("gho_super_secret" in onDisk, "the token must not be stored in clear text")
            assertFalse("hunter2" in onDisk, "the password must not be stored in clear text")
        }
    }

    @Test
    fun `the scp-like remote form resolves its host`() {
        assertEquals("github.com", hostOf("git@github.com:octocat/hello.git"))
        assertEquals("github.com", hostOf("https://github.com/octocat/hello.git"))
        assertEquals("git.example.com", hostOf("ssh://git@git.example.com/team/app.git"))
        assertNull(hostOf("   "))
    }

    @Test
    fun `an api host serves the plain host of the same forge`() {
        assertTrue(sameHost("api.github.com", "github.com"))
        assertTrue(sameHost("github.com", "github.com"))
        assertFalse(sameHost("api.github.com", "gitlab.com"))
    }
}
