package com.tyron.builder.api.dsl

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer

interface Execution {
    /** Container where profiles can be declared. */
    val profiles: NamedDomainObjectContainer<ExecutionProfile>

    /** Container where profiles can be declared. */
    fun profiles(action: Action<NamedDomainObjectContainer<ExecutionProfile>>)

    /** Container where profiles can be declared. */
    fun profiles(action: NamedDomainObjectContainer<ExecutionProfile>.() -> Unit)

    /** Select execution profile */
    var defaultProfile: String?
}