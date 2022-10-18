package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.AndroidGradlePluginProjectFlags
import java.io.Serializable

/**
 * Implementation of [AndroidGradlePluginProjectFlags] for serialization via the Tooling API.
 */
data class AndroidGradlePluginProjectFlagsImpl(
    val booleanFlagMap: Map<AndroidGradlePluginProjectFlags.BooleanFlag, Boolean>
) : AndroidGradlePluginProjectFlags, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
        private val flagByName = AndroidGradlePluginProjectFlags.BooleanFlag.values().associateBy { it.name }
    }

    override fun getFlagValue(flagName: String): Boolean? {
        // Returns null if the flag is unknown, or if the value is not set.
        return flagByName[flagName]?.let { booleanFlagMap[it] }
    }
}
