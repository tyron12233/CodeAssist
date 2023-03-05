package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.dexing.getR8Version
import com.google.common.annotations.VisibleForTesting
import java.io.Serializable

data class VersionedCodeShrinker(val version: String) : Serializable {
    companion object {
        @JvmStatic
        fun create() = VersionedCodeShrinker(parseVersionString(getR8Version()))

        private val versionPattern = """[^\s.]+(?:\.[^\s.]+)+""".toRegex()

        @VisibleForTesting
        internal fun parseVersionString(version: String): String {
            val matcher = versionPattern.find(version)
            return if (matcher != null) {
                LoggerWrapper.getLogger(VersionedCodeShrinker::class.java)
                    .verbose("Parsed shrinker version: ${matcher.groupValues[0]}")
                matcher.groupValues[0]
            } else {
                LoggerWrapper.getLogger(VersionedCodeShrinker::class.java)
                    .warning("Cannot parse shrinker version, assuming 0.0.0")
                "0.0.0"
            }
        }
    }
}
