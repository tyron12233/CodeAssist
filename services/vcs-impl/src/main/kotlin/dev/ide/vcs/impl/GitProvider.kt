package dev.ide.vcs.impl

import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsMessage
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsProvider
import dev.ide.vcs.VcsRepository
import dev.ide.vcs.VcsText
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Git [VcsProvider], backed by JGit. [configDir] is where the user-level Git config lives (see
 * [GitEnvironment]); the host passes an app-owned directory so nothing is read from or written to the
 * device's home directory.
 *
 * Every operation catches [Throwable], not [Exception]. JGit is built for a desktop JVM, so a call into it
 * can fail with a [LinkageError] rather than an exception when a method it was compiled against is missing
 * from the device's runtime. Catching only [Exception] let such an error unwind past the caller and take the
 * process down instead of reporting a failed Git operation.
 */
class GitProvider(configDir: Path) : VcsProvider {

    init {
        GitEnvironment.configure(configDir)
    }

    override val id: String = "git"

    override val displayName: String = "Git"

    override fun findRoot(dir: Path): Path? {
        var candidate: Path? = dir.toAbsolutePath().normalize()
        while (candidate != null) {
            val dotGit = candidate.resolve(Constants.DOT_GIT)
            // A worktree or submodule records `.git` as a file pointing at the real directory.
            if (Files.isDirectory(dotGit) || Files.isRegularFile(dotGit)) return candidate
            candidate = candidate.parent
        }
        return null
    }

    override fun open(root: Path): VcsRepository {
        val gitDir = root.resolve(Constants.DOT_GIT)
        if (!Files.exists(gitDir)) throw VcsException(msg(VcsMessage.NOT_A_REPOSITORY, root.fileName))
        return try {
            val repo = FileRepositoryBuilder()
                .setWorkTree(root.toFile())
                .findGitDir(root.toFile())
                .readEnvironment()
                .build()
            GitRepository(Git(repo), root)
        } catch (e: Throwable) {
            throw VcsException(msg(VcsMessage.WITH_REASON, msg(VcsMessage.OPEN_FAILED, root), VcsText.literal(e.reason())), e)
        }
    }

    override fun init(dir: Path, defaultBranch: String): VcsRepository {
        return try {
            Files.createDirectories(dir)
            val git = Git.init()
                .setDirectory(dir.toFile())
                .setInitialBranch(defaultBranch)
                .call()
            GitRepository(git, dir)
        } catch (e: Throwable) {
            throw VcsException(msg(VcsMessage.WITH_REASON, msg(VcsMessage.INIT_FAILED, dir), VcsText.literal(e.reason())), e)
        }
    }

    override fun clone(
        url: String,
        target: Path,
        branch: String?,
        depth: Int,
        auth: VcsCredentials?,
        progress: VcsProgress,
    ): VcsRepository {
        val dir: File = target.toFile()
        if (dir.exists() && dir.list()?.isNotEmpty() == true) {
            throw VcsException(msg(VcsMessage.FOLDER_NOT_EMPTY, target.fileName))
        }
        return try {
            val command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setProgressMonitor(GitProgressMonitor(progress))
                .setCredentialsProvider(auth.toJGit())
                .setTimeout(CLONE_TIMEOUT_SECONDS)
            if (!branch.isNullOrBlank()) command.setBranch(branch)
            if (depth > 0) command.setDepth(depth)
            GitRepository(command.call(), target)
        } catch (e: Throwable) {
            // A failed clone leaves a partial directory behind; clearing it keeps a retry from tripping the
            // "already exists" check above.
            runCatching { dir.deleteRecursively() }
            throw e.asVcsFailure(msg(VcsMessage.CLONE_FAILED, redactUrl(url)), host = hostOf(url))
        }
    }
}

/** Seconds a clone may sit without the server answering before it fails, so a dead connection cannot hang it. */
private const val CLONE_TIMEOUT_SECONDS = 60
