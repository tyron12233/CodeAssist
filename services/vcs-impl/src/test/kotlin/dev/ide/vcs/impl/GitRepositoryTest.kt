package dev.ide.vcs.impl

import dev.ide.testkit.withTempDir
import dev.ide.vcs.VcsAuthor
import dev.ide.vcs.VcsChangeArea
import dev.ide.vcs.VcsChangeKind
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsMergeResult
import dev.ide.vcs.VcsRepository
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Git engine over a real working copy: every assertion drives JGit against a temp directory, so what is
 * verified is the actual on-disk behaviour rather than a mock of it.
 */
class GitRepositoryTest {

    private val author = VcsAuthor("Test User", "test@example.com")

    /** Open a fresh repository in [dir], with an identity configured so commits work. */
    private fun repo(dir: Path, configDir: Path): VcsRepository {
        val provider = GitProvider(configDir)
        val repository = provider.init(dir.resolve("work"))
        repository.setIdentity(author)
        return repository
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `init reports an unborn repository with no changes`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                val status = repository.status()
                assertTrue(status.unborn, "a fresh repository has no commits")
                assertTrue(status.clean)
                assertEquals("main", status.branch)
            }
        }
    }

    @Test
    fun `an untracked file becomes staged then committed`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "src/Main.kt", "fun main() {}\n")

                val untracked = repository.status()
                assertEquals(1, untracked.unstaged.size)
                assertEquals(VcsChangeKind.UNTRACKED, untracked.unstaged.single().kind)
                assertEquals("src/Main.kt", untracked.unstaged.single().path)

                repository.stage(listOf("src/Main.kt"))
                val staged = repository.status()
                assertEquals(1, staged.staged.size)
                assertEquals(VcsChangeArea.STAGED, staged.staged.single().area)
                assertTrue(staged.unstaged.isEmpty())

                val commit = repository.commit("Add main")
                assertEquals("Add main", commit.summary)
                assertEquals(author.name, commit.author.name)

                val after = repository.status()
                assertTrue(after.clean)
                assertFalse(after.unborn)
                assertEquals(commit.id, after.head?.id)
            }
        }
    }

    @Test
    fun `unstage returns a staged file to the working tree`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "one\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                write(repository.root, "a.txt", "two\n")
                repository.stage(listOf("a.txt"))
                assertEquals(1, repository.status().staged.size)

                repository.unstage(listOf("a.txt"))
                val status = repository.status()
                assertTrue(status.staged.isEmpty())
                assertEquals(VcsChangeKind.MODIFIED, status.unstaged.single().kind)
            }
        }
    }

    @Test
    fun `discard restores a tracked file and deletes an untracked one`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "tracked.txt", "original\n")
                repository.stage(listOf("tracked.txt"))
                repository.commit("first")

                write(repository.root, "tracked.txt", "edited\n")
                write(repository.root, "fresh.txt", "new\n")

                repository.discard(listOf("tracked.txt", "fresh.txt"))

                assertEquals("original\n", Files.readString(repository.root.resolve("tracked.txt")))
                assertFalse(Files.exists(repository.root.resolve("fresh.txt")))
                assertTrue(repository.status().clean)
            }
        }
    }

    @Test
    fun `staging a deleted file records the removal`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "gone.txt", "bye\n")
                repository.stage(listOf("gone.txt"))
                repository.commit("first")

                Files.delete(repository.root.resolve("gone.txt"))
                assertEquals(VcsChangeKind.DELETED, repository.status().unstaged.single().kind)

                repository.stage(listOf("gone.txt"))
                val status = repository.status()
                assertEquals(VcsChangeKind.DELETED, status.staged.single().kind)
                assertTrue(status.unstaged.isEmpty())
            }
        }
    }

    @Test
    fun `log lists commits newest first and narrows to one path`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "a\n")
                repository.stage(listOf("a.txt"))
                repository.commit("add a")

                write(repository.root, "b.txt", "b\n")
                repository.stage(listOf("b.txt"))
                repository.commit("add b")

                val all = repository.log()
                assertEquals(listOf("add b", "add a"), all.map { it.summary })
                assertEquals(7, all.first().shortId.length)

                val onlyA = repository.log(path = "a.txt")
                assertEquals(listOf("add a"), onlyA.map { it.summary })
            }
        }
    }

    @Test
    fun `commit detail reports the touched paths and line counts`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "one\ntwo\n")
                repository.stage(listOf("a.txt"))
                val first = repository.commit("add a")

                val detail = repository.commitDetail(first.id)
                assertEquals(1, detail.changes.size)
                assertEquals("a.txt", detail.changes.single().path)
                assertEquals(2, detail.insertions)
                assertEquals(0, detail.deletions)
            }
        }
    }

    @Test
    fun `diff renders the working tree change as a unified patch`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "one\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                write(repository.root, "a.txt", "two\n")
                val diff = repository.diff("a.txt")
                assertFalse(diff.binary)
                assertContains(diff.text, "-one")
                assertContains(diff.text, "+two")
                assertEquals(1, diff.insertions)
                assertEquals(1, diff.deletions)
            }
        }
    }

    @Test
    fun `show reads a path at a revision`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "committed\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")
                write(repository.root, "a.txt", "edited\n")

                assertEquals("committed\n", repository.show("a.txt"))
                assertNull(repository.show("missing.txt"))
            }
        }
    }

    @Test
    fun `branches are created, listed, and switched`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "a\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                repository.createBranch("feature", checkout = true)
                assertEquals("feature", repository.status().branch)

                val branches = repository.branches()
                assertEquals(setOf("main", "feature"), branches.map { it.name }.toSet())
                assertEquals("feature", branches.single { it.current }.name)

                repository.checkout("main")
                assertEquals("main", repository.status().branch)

                repository.deleteBranch("feature", force = true)
                assertEquals(listOf("main"), repository.branches().map { it.name })
            }
        }
    }

    @Test
    fun `deleting the current branch is refused with a message`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "a\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                val failure = assertFailsWith<VcsException> { repository.deleteBranch("main") }
                assertContains(failure.message.orEmpty(), "current branch")
            }
        }
    }

    @Test
    fun `an invalid branch name is rejected before Git sees it`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                assertFailsWith<VcsException> { repository.createBranch("bad name") }
                assertFailsWith<VcsException> { repository.createBranch("  ") }
            }
        }
    }

    @Test
    fun `merging a diverged branch reports the conflict`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "base\n")
                repository.stage(listOf("a.txt"))
                repository.commit("base")

                repository.createBranch("feature", checkout = true)
                write(repository.root, "a.txt", "feature\n")
                repository.stage(listOf("a.txt"))
                repository.commit("feature edit")

                repository.checkout("main")
                write(repository.root, "a.txt", "main\n")
                repository.stage(listOf("a.txt"))
                repository.commit("main edit")

                val result = repository.merge("feature")
                assertEquals(VcsMergeResult.Status.CONFLICTS, result.status)
                assertEquals(listOf("a.txt"), result.conflicts)
                assertEquals(1, repository.status().conflicted.size)

                repository.abortMerge()
                assertTrue(repository.status().conflicted.isEmpty())
            }
        }
    }

    @Test
    fun `a fast-forward merge advances the branch`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "base\n")
                repository.stage(listOf("a.txt"))
                repository.commit("base")

                repository.createBranch("feature", checkout = true)
                write(repository.root, "b.txt", "b\n")
                repository.stage(listOf("b.txt"))
                repository.commit("add b")

                repository.checkout("main")
                val result = repository.merge("feature")
                assertEquals(VcsMergeResult.Status.FAST_FORWARD, result.status)
                assertEquals(listOf("add b", "base"), repository.log().map { it.summary })
            }
        }
    }

    @Test
    fun `stash removes the changes and applying restores them`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "one\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                write(repository.root, "a.txt", "edited\n")
                assertTrue(repository.stashPush("work in progress"))
                assertTrue(repository.status().clean)

                val stashes = repository.stashes()
                assertEquals(1, stashes.size)
                assertContains(stashes.single().message, "work in progress")

                repository.stashApply(0)
                assertEquals("edited\n", Files.readString(repository.root.resolve("a.txt")))
                assertTrue(repository.stashes().isEmpty())
            }
        }
    }

    @Test
    fun `stashing a clean tree reports that there was nothing to do`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                write(repository.root, "a.txt", "one\n")
                repository.stage(listOf("a.txt"))
                repository.commit("first")

                assertFalse(repository.stashPush("nothing"))
            }
        }
    }

    @Test
    fun `remotes are added, listed, and removed`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                assertTrue(repository.remotes().isEmpty())

                repository.addRemote("origin", "https://example.com/owner/repo.git")
                assertEquals("https://example.com/owner/repo.git", repository.remotes().single().fetchUrl)

                // Adding the same name again replaces the URL rather than failing.
                repository.addRemote("origin", "https://example.com/owner/other.git")
                assertEquals("https://example.com/owner/other.git", repository.remotes().single().fetchUrl)

                repository.removeRemote("origin")
                assertTrue(repository.remotes().isEmpty())
            }
        }
    }

    @Test
    fun `ignore appends only patterns that are not already present`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                repository.ignore(listOf("build/", ".platform/"))
                repository.ignore(listOf("build/", "*.apk"))

                val lines = Files.readAllLines(repository.root.resolve(".gitignore")).filter { it.isNotBlank() }
                assertEquals(listOf("build/", ".platform/", "*.apk"), lines)
            }
        }
    }

    @Test
    fun `committing without an identity fails with a message the user can act on`() {
        withTempDir("vcs") { dir ->
            val provider = GitProvider(dir.resolve("config"))
            provider.init(dir.resolve("work")).use { repository ->
                write(repository.root, "a.txt", "a\n")
                repository.stage(listOf("a.txt"))
                // No identity is configured, and the user config lives in an empty app directory.
                val failure = assertFailsWith<VcsException> { repository.commit("no identity") }
                assertTrue(failure.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun `findRoot walks up from a nested directory`() {
        withTempDir("vcs") { dir ->
            val provider = GitProvider(dir.resolve("config"))
            provider.init(dir.resolve("work")).use { repository ->
                val nested = repository.root.resolve("src/main/kotlin")
                Files.createDirectories(nested)
                assertEquals(repository.root, provider.findRoot(nested))
            }
            assertNull(provider.findRoot(dir.resolve("elsewhere").also { Files.createDirectories(it) }))
        }
    }

    @Test
    fun `opening a directory that is not a checkout fails`() {
        withTempDir("vcs") { dir ->
            val provider = GitProvider(dir.resolve("config"))
            val plain = dir.resolve("plain")
            Files.createDirectories(plain)
            assertFailsWith<VcsException> { provider.open(plain) }
        }
    }

    @Test
    fun `identity round-trips through the repository config`() {
        withTempDir("vcs") { dir ->
            repo(dir, dir.resolve("config")).use { repository ->
                val identity = assertNotNull(repository.identity())
                assertEquals("Test User", identity.name)
                assertEquals("test@example.com", identity.email)
            }
        }
    }
}
