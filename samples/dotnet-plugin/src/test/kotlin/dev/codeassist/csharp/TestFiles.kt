package dev.codeassist.csharp

import java.nio.file.Files
import java.nio.file.Path

/**
 * A new empty directory for one test, removed when the JVM exits.
 *
 * Not a JUnit rule: a rule only runs under a runner that applies it, and a test that touches the folder
 * before that fails with "the temporary folder has not yet been created".
 */
internal fun newTempDir(): Path {
    val dir = Files.createTempDirectory("cs-plugin-test")
    Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
    return dir
}
