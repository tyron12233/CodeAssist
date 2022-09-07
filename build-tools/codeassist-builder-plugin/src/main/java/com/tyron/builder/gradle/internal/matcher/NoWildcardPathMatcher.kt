package com.tyron.builder.gradle.internal.matcher

import java.io.File
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.Matcher
import java.util.regex.Pattern

class NoWildcardPathMatcher(matcher: Matcher): PathMatcher {

    val path: String

    init {
        if (!matcher.matches())
            throw IllegalArgumentException("matcher $matcher does not match this factory")
        path = matcher.group(1)
    }

    companion object {
        val pattern: Pattern= Pattern.compile("(/[^*{}]*)")
        fun factory() = object: GlobPathMatcherFactory {
            override fun build(glob: Matcher) = NoWildcardPathMatcher(glob)
            override fun pattern()= pattern
        }
    }

    override fun matches(p0: Path?): Boolean {
        val pathAsString = p0?.toString()?.replace(File.separatorChar, '/')
        return path == pathAsString
    }
}
