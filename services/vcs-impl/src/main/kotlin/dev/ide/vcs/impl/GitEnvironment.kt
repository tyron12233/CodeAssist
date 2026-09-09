package dev.ide.vcs.impl

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Points JGit's configuration lookup at a directory the app owns.
 *
 * Out of the box JGit resolves the user config from `$HOME/.gitconfig` and discovers the system config by
 * running `git config --system`. Neither works on a phone: `user.home` is `/`, which is not writable, and
 * there is no `git` binary to run, so every config read pays a failed process spawn and every config write
 * fails. Installing a [SystemReader] that resolves all three files under an app directory removes both.
 *
 * Idempotent, and safe to call from any thread: the first call wins.
 */
object GitEnvironment {

    /** File name of the user-level config inside the configured directory. */
    private const val USER_CONFIG = "gitconfig"

    /** File name of JGit's own config (filesystem timer resolution and similar cached probes). */
    private const val JGIT_CONFIG = "jgitconfig"

    /** A system-level config we deliberately leave absent, so nothing is inherited from the device. */
    private const val SYSTEM_CONFIG = "gitconfig.system"

    @Volatile
    private var configuredDir: Path? = null

    /** The directory the configs resolve under, or null before [configure]. */
    val configDir: Path? get() = configuredDir

    /**
     * Resolve JGit's user, system, and JGit-own configs under [dir], creating it if needed. Later calls with
     * a different directory are ignored so the reader stays stable for the process lifetime.
     */
    @Synchronized
    fun configure(dir: Path) {
        if (configuredDir != null) return
        Files.createDirectories(dir)
        configuredDir = dir
        SystemReader.setInstance(AppDirSystemReader(SystemReader.getInstance(), dir))
    }

    private class AppDirSystemReader(delegate: SystemReader, private val dir: Path) : SystemReader.Delegate(delegate) {
        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(dir.toFile(), USER_CONFIG), fs)

        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(dir.toFile(), SYSTEM_CONFIG), fs)

        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(dir.toFile(), JGIT_CONFIG), fs)
    }
}
