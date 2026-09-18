package dev.ide.platform

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [normalizePath] and friends against `java.nio.file.Path`, the thing they replace.
 *
 * The project model used to hold `Path`s and now holds strings, so every path it resolves goes through these
 * functions. A disagreement with `Path` would not announce itself: a module root that normalizes one segment
 * differently still looks like a path, and the failure surfaces later as a file the model cannot find. So
 * the oracle is the implementation that was there before, asked the same questions.
 *
 * Where the contracts deliberately differ ([relativizePath] on a path outside the base, `..` past a relative
 * root) the test states the difference rather than asserting equality; those are documented on the functions.
 */
class PathNamesOracleTest {

    private fun oracleNormalize(p: String): String =
        Paths.get(p).normalize().toString().replace('\\', '/')

    @Test
    fun `normalize agrees with java nio on ordinary paths`() {
        val cases = listOf(
            "/a/b/c",
            "/a/./b",
            "/a/b/../c",
            "/a/b/../../c",
            "/a//b///c",
            "a/b/c",
            "a/./b",
            "a/b/../c",
            "/a/b/",
            "/a/b/c/../..",
            "/",
        )
        for (case in cases) {
            assertEquals(oracleNormalize(case), normalizePath(case), "normalizing '$case'")
        }
    }

    @Test
    fun `resolve agrees with java nio`() {
        val cases = listOf(
            "/work" to "app",
            "/work" to "app/src/main",
            "/work" to "./app",
            "/work" to "../sibling",
            "/work" to "/absolute/elsewhere",
            "/work/app" to "..",
        )
        for ((base, rel) in cases) {
            val oracle = Paths.get(base).resolve(rel).normalize().toString().replace('\\', '/')
            assertEquals(oracle, resolvePath(base, rel), "resolving '$rel' against '$base'")
        }
    }

    /** The model's own special case: a project whose root-relative path is `""` IS the workspace root. */
    @Test
    fun `an empty or dot relative path resolves to the base itself`() {
        assertEquals("/work", resolvePath("/work", ""))
        assertEquals("/work", resolvePath("/work", "."))
    }

    @Test
    fun `relativize agrees with java nio for a path under the base`() {
        val cases = listOf(
            "/work" to "/work/app",
            "/work" to "/work/app/src/main/kotlin",
            "/work" to "/work",
        )
        for ((base, path) in cases) {
            val oracle = Paths.get(base).relativize(Paths.get(path)).toString().replace('\\', '/')
            assertEquals(oracle, relativizePath(base, path), "relativizing '$path' against '$base'")
        }
    }

    /**
     * The documented divergence. `Path.relativize` climbs out with `..`; this answers the path whole,
     * because the model stores only paths under the root and a climbing one there is a bug, not a shorthand.
     */
    @Test
    fun `relativize answers a path outside the base whole rather than climbing out`() {
        assertEquals("../elsewhere", Paths.get("/work").relativize(Paths.get("/elsewhere")).toString())
        assertEquals("/elsewhere", relativizePath("/work", "/elsewhere"))
    }

    @Test
    fun `parent and name agree with java nio`() {
        for (case in listOf("/a/b/c", "/a/b", "/a", "a/b", "/work/app/module.toml")) {
            val p = Paths.get(case)
            assertEquals(p.parent?.toString()?.replace('\\', '/'), parentPath(case), "parent of '$case'")
            assertEquals(p.fileName.toString(), fileName(case), "name of '$case'")
        }
    }

    @Test
    fun `absoluteness agrees with java nio`() {
        for (case in listOf("/a/b", "a/b", "/", "")) {
            assertEquals(Paths.get(case).isAbsolute, isAbsolutePath(case), "'$case'")
        }
    }

    /** Windows-style separators can still arrive in a file read off disk; they fold to the one separator. */
    @Test
    fun `backslashes fold to the separator`() {
        assertEquals("/a/b/c", normalizePath("\\a\\b\\c"))
        assertEquals("app/src", normalizePath("app\\src"))
    }
}
