// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Path arithmetic on strings: joining, normalizing, relativizing.
 *
 * `java.nio.file.Path` has no counterpart off the JVM, so anything that has to name a file from common code
 * names it as a string — which is also what the file system here takes. These are the operations a model
 * built out of RELATIVE paths needs to turn them into absolute ones and back.
 *
 * `/` is the separator, on every platform. iOS uses it, and the JVM accepts it on Windows; nothing in this
 * build targets a platform where it is wrong. A path read off disk on Windows can still arrive with
 * backslashes, so [normalizePath] folds them.
 *
 * A path is absolute when it starts at a root: `/`, or a Windows drive (`C:/`). Treating `C:/Users/...` as relative made [resolvePath] append one absolute path to another
 * (`C:/proj/app/C:/Users/...`), which Windows rejects as "Illegal char <:>".
 *
 * These are PURE: nothing here touches the file system, so `..` is resolved lexically and a symbolic link is
 * not followed. That matches `Path.normalize`, and differs from `Path.toRealPath`.
 */

/** The platform-independent separator. */
const val PATH_SEPARATOR: Char = '/'

/**
 * [path] with `.` segments dropped, `..` segments applied to their parent, and repeated separators collapsed.
 *
 * The root is kept (the path stays absolute) and a trailing separator is dropped. A `..` that would climb
 * past the root of an absolute path is discarded, as `Path.normalize` discards it; on a relative path it is
 * kept, because there is no root to climb past and dropping it would change what the path means.
 */
fun normalizePath(path: String): String {
    val folded = path.replace('\\', PATH_SEPARATOR)
    val root = rootOf(folded)
    val out = ArrayList<String>()
    for (segment in folded.substring(minOf(root.length, folded.length)).split(PATH_SEPARATOR)) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> {
                val last = out.lastOrNull()
                if (last != null && last != "..") out.removeAt(out.size - 1)
                else if (root.isEmpty()) out.add(segment)  // nothing to climb: keep it, it still means something
            }
            else -> out.add(segment)
        }
    }
    return root + out.joinToString(PATH_SEPARATOR.toString())
}

/**
 * The root [folded] (separators already `/`) starts with: `/`, `C:/` for a Windows drive (a bare `C:` too), or
 * `""` for a relative path. A UNC share (`//server/share`) still collapses to `/server/share`, as it always has: keeping
 * `//` would change the identity of the `//Users/...` a string join produces on Unix.
 */
private fun rootOf(folded: String): String = when {
    folded.length >= 2 && folded[1] == ':' && folded[0].isLetter() &&
        (folded.length == 2 || folded[2] == PATH_SEPARATOR) -> "${folded[0]}:$PATH_SEPARATOR"
    folded.startsWith(PATH_SEPARATOR) -> PATH_SEPARATOR.toString()
    else -> ""
}

/**
 * [base] with [rel] appended, normalized. An absolute [rel] replaces [base] entirely, as `Path.resolve` does.
 *
 * An empty or `.` [rel] answers [base] normalized, which is what a model whose root-relative path is `""`
 * (the workspace root itself) depends on.
 */
fun resolvePath(base: String, rel: String): String = when {
    rel.isEmpty() || rel == "." -> normalizePath(base)
    isAbsolutePath(rel) -> normalizePath(rel)
    base.isEmpty() -> normalizePath(rel)
    else -> normalizePath("${base.trimEnd(PATH_SEPARATOR)}$PATH_SEPARATOR$rel")
}

/**
 * [path] expressed relative to [base], or [path] itself when it is not under [base].
 *
 * Returns `""` when the two are the same, which is how the workspace root relativizes against itself.
 * Unlike `Path.relativize` this never produces a `..` chain: a path outside [base] is answered whole, since
 * the model only ever stores paths under the root and a climbing path there is a bug rather than a
 * shorthand.
 */
fun relativizePath(base: String, path: String): String {
    val b = normalizePath(base)
    val p = normalizePath(path)
    if (p == b) return ""
    val prefix = if (b.endsWith(PATH_SEPARATOR)) b else "$b$PATH_SEPARATOR"
    return if (p.startsWith(prefix)) p.substring(prefix.length) else p
}

/** Everything before the last separator, or null when [path] has no parent (a bare name, or the root). */
fun parentPath(path: String): String? {
    val p = normalizePath(path)
    val root = rootOf(p)
    if (p == root) return null
    val cut = p.lastIndexOf(PATH_SEPARATOR)
    return when {
        cut < root.length -> root.ifEmpty { null }
        else -> p.substring(0, cut)
    }
}

/** The last segment of [path]: a file's name, or a directory's. Empty for a root. */
fun fileName(path: String): String {
    val p = normalizePath(path)
    return if (p == rootOf(p)) "" else p.substringAfterLast(PATH_SEPARATOR)
}

/** True when [path] starts at a root (`/`, `C:/`) rather than at wherever the caller happens to be. */
fun isAbsolutePath(path: String): Boolean = rootOf(path.replace('\\', PATH_SEPARATOR)).isNotEmpty()
