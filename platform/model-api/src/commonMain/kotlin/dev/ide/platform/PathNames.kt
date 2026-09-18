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
 * These are PURE: nothing here touches the file system, so `..` is resolved lexically and a symbolic link is
 * not followed. That matches `Path.normalize`, and differs from `Path.toRealPath`.
 */

/** The platform-independent separator. */
const val PATH_SEPARATOR: Char = '/'

/**
 * [path] with `.` segments dropped, `..` segments applied to their parent, and repeated separators collapsed.
 *
 * A leading `/` is kept (the path stays absolute) and a trailing one is dropped. A `..` that would climb
 * past the root of an absolute path is discarded, as `Path.normalize` discards it; on a relative path it is
 * kept, because there is no root to climb past and dropping it would change what the path means.
 */
fun normalizePath(path: String): String {
    val folded = path.replace('\\', PATH_SEPARATOR)
    val absolute = folded.startsWith(PATH_SEPARATOR)
    val out = ArrayList<String>()
    for (segment in folded.split(PATH_SEPARATOR)) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> {
                val last = out.lastOrNull()
                if (last != null && last != "..") out.removeAt(out.size - 1)
                else if (!absolute) out.add(segment)  // nothing to climb: keep it, it still means something
            }
            else -> out.add(segment)
        }
    }
    val joined = out.joinToString(PATH_SEPARATOR.toString())
    return if (absolute) "$PATH_SEPARATOR$joined" else joined
}

/**
 * [base] with [rel] appended, normalized. An absolute [rel] replaces [base] entirely, as `Path.resolve` does.
 *
 * An empty or `.` [rel] answers [base] normalized, which is what a model whose root-relative path is `""`
 * (the workspace root itself) depends on.
 */
fun resolvePath(base: String, rel: String): String = when {
    rel.isEmpty() || rel == "." -> normalizePath(base)
    rel.startsWith(PATH_SEPARATOR) || rel.replace('\\', PATH_SEPARATOR).startsWith(PATH_SEPARATOR) ->
        normalizePath(rel)
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
    if (p == PATH_SEPARATOR.toString()) return null
    val cut = p.lastIndexOf(PATH_SEPARATOR)
    return when {
        cut < 0 -> null
        cut == 0 -> PATH_SEPARATOR.toString()
        else -> p.substring(0, cut)
    }
}

/** The last segment of [path]: a file's name, or a directory's. */
fun fileName(path: String): String = normalizePath(path).substringAfterLast(PATH_SEPARATOR)

/** True when [path] starts at a root rather than at wherever the caller happens to be. */
fun isAbsolutePath(path: String): Boolean = path.replace('\\', PATH_SEPARATOR).startsWith(PATH_SEPARATOR)
