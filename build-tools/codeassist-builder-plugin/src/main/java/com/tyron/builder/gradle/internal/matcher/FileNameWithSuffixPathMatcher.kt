package com.tyron.builder.gradle.internal.matcher

import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.Matcher
import java.util.regex.Pattern

open class FileNameWithSuffixPathMatcher(matcher: Matcher) : PathMatcher {

    private val fileName: String

    init {
        if (!matcher.matches())
            throw IllegalArgumentException("matcher $matcher does not match this factory")
        fileName = matcher.group(1)
    }

    companion object {
        // **/foo or **/*foo
        private val pattern: Pattern= Pattern.compile("\\*\\*/\\*?([^/*{}]*)")
        fun factory()= object: GlobPathMatcherFactory {
            override fun pattern()= pattern
            override fun build(glob: Matcher)= FileNameWithSuffixPathMatcher(glob)
        }
    }

    override fun matches(path: Path?): Boolean {
        return path?.parent != null &&
            path.fileName?.toString()?.endsWith(fileName) ?: false
    }
}
