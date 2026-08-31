package dev.ide.vcs.impl

import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsProvider
import dev.ide.vcs.VcsRepository
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
        if (!Files.exists(gitDir)) throw VcsException("${root.fileName} is not a Git repository")
        return try {
            val repo = FileRepositoryBuilder()
                .setWorkTree(root.toFile())
                .findGitDir(root.toFile())
                .readEnvironment()
                .build()
            GitRepository(Git(repo), root)
        } catch (e: Exception) {
            throw VcsException("Could not open the Git repository at $root: ${e.reason()}", e)
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
        } catch (e: Exception) {
            throw VcsException("Could not create a Git repository in $dir: ${e.reason()}", e)
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
            throw VcsException("${target.fileName} already exists and is not empty")
        }
        return try {
            val command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setProgressMonitor(GitProgressMonitor(progress))
                .setCredentialsProvider(auth.toJGit())
            if (!branch.isNullOrBlank()) command.setBranch(branch)
            if (depth > 0) command.setDepth(depth)
            GitRepository(command.call(), target)
        } catch (e: Exception) {
            // A failed clone leaves a partial directory behind; clearing it keeps a retry from tripping the
            // "already exists" check above.
            runCatching { dir.deleteRecursively() }
            throw e.asVcsFailure("Could not clone $url", host = hostOf(url))
        }
    }
}
