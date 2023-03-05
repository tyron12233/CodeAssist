package com.tyron.builder.api.dsl

import org.gradle.api.Action
import org.gradle.api.Named

interface ExecutionProfile: Named {
    /** Specify R8 execution options. */
    val r8: ToolOptions

    /** Specify R8 execution options. */
    fun r8(action: Action<ToolOptions>)

    /** Specify R8 execution options. */
    fun r8(action: ToolOptions.() -> Unit)
}