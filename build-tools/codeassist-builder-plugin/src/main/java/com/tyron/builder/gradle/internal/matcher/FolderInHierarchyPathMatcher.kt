package com.tyron.builder.gradle.internal.matcher

import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.Matcher
import java.util.regex.Pattern

class FolderInHierarchyPathMatcher(val matcher: Matcher) : PathMatcher {

    private val prefix: String = if ("*" != matcher.group(1)) matcher.group(1) else ""
    private val exactMatching= "*" != matcher.group(1) && !matcher.group(2).startsWith("*")
    private val suffix: String = if (matcher.group(2).startsWith("*"))
        matcher.group(2).substring(1) else matcher.group(2)
    private val exactMatchingString = "$prefix$suffix"

    init {
        if (!matcher.matches())
            throw IllegalArgumentException("matcher $matcher does not match this factory")
    }

    companion object {
        // **/foo/** **/.*/** **/blah*/** **/*foo/**
        val pattern: Pattern = Pattern.compile("\\*\\*/([^/*{}]*)([^/{}]*)/\\*\\*")
        fun factory(): GlobPathMatcherFactory {
            return object: GlobPathMatcherFactory {
                override fun pattern()= pattern
                override fun build(glob: Matcher)= FolderInHierarchyPathMatcher(glob)
            }
        }
    }

    override fun matches(path: Path?): Boolean {

        val pathAsString = path?.parent?.toString() ?: return false
        // if the complete path does not contain prefix and suffix anywhere, no need to continue
        if (!pathAsString.contains(prefix) || !pathAsString.contains(suffix)) return false

        var currentFolder = path
        while (currentFolder != null) {
            val folderAsString = currentFolder.fileName?.toString() ?: return false
            if (exactMatching) {
                if (folderAsString == exactMatchingString) {
                    return true
                }
            } else {
                if (folderAsString.startsWith(prefix) && folderAsString.endsWith(suffix)) {
                    // even though we are matching, we cannot be the top level folder.
                    return currentFolder.parent != null
                }
            }
            currentFolder = currentFolder.parent
        }
        return false
    }
}
