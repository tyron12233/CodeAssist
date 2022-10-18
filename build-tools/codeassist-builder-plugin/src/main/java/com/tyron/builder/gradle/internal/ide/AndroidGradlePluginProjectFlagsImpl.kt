package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.AndroidGradlePluginProjectFlags
import java.io.Serializable

data class AndroidGradlePluginProjectFlagsImpl(private val booleanFlagMap: Map<AndroidGradlePluginProjectFlags.BooleanFlag, Boolean>) : AndroidGradlePluginProjectFlags, Serializable {
    override fun getBooleanFlagMap(): Map<AndroidGradlePluginProjectFlags.BooleanFlag, Boolean> = booleanFlagMap
}