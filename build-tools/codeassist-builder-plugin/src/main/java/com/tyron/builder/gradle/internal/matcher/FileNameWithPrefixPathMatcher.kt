package com.tyron.builder.gradle.internal.matcher

import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.Matcher
import java.util.regex.Pattern

open class FileNameWithPrefixPathMatcher(matcher: Matcher) : PathMatcher {

    val prefix: String

    init {
        if (!matcher.matches())
            throw IllegalArgumentException("matcher $matcher does not match this factory")
        prefix = matcher.group(1)
    }

    companion object {
        // **/foo*
        private val pattern: Pattern= Pattern.compile("\\*\\*/([^*]+)\\*")
        fun factory() = object: GlobPathMatcherFactory {
            override fun pattern()= pattern
            override fun build(glob: Matcher)= FileNameWithPrefixPathMatcher(glob)
        }
    }

    override fun matches(p0: Path?): Boolean {
        return p0?.fileName?.toString()?.startsWith(prefix) ?: false
    }
}
