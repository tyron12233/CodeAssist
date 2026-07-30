package dev.ide.testkit

import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Run [block] with a fresh temp directory, deleting it recursively afterward. The single replacement for the
 * hand-rolled `Files.createTempDirectory(...)` + `finally { dir.toFile().deleteRecursively() }` idiom that
 * appeared in hundreds of tests.
 */
inline fun <T> withTempDir(prefix: String = "ide-test", block: (Path) -> T): T {
    val dir = Files.createTempDirectory(prefix)
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/**
 * A temp directory paired with a lazily-created [PlatformCore], torn down together — the replacement for the
 * common `val dir = createTempDirectory(...); val platform = PlatformCore(); try { ... } finally {
 * platform.dispose(); dir.toFile().deleteRecursively() }` shape. The platform is only constructed (and only
 * disposed) if [platform] is actually accessed, so tests that just need a directory pay nothing for it.
 *
 * Use with [testEnv] (`testEnv { env -> ... }`) or directly as an [AutoCloseable] (`TestEnv().use { ... }`).
 */
class TestEnv(prefix: String = "ide-test") : AutoCloseable {
    val dir: Path = Files.createTempDirectory(prefix)

    private var built: PlatformCore? = null
    val platform: PlatformCore
        get() = built ?: PlatformCore().also { built = it }

    override fun close() {
        built?.dispose()
        dir.toFile().deleteRecursively()
    }
}

/** Run [block] with a [TestEnv], closing it (dispose platform + delete dir) afterward. */
inline fun <T> testEnv(prefix: String = "ide-test", block: (TestEnv) -> T): T =
    TestEnv(prefix).use(block)
