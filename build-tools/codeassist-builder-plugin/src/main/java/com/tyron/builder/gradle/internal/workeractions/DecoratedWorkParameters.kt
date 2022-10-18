package com.tyron.builder.gradle.internal.workeractions

import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

/**
 * Decorated [WorkParameters] that will remember task path and worker key to be able to sent
 * sensible events to the analytics build service.
 */
interface DecoratedWorkParameters : WorkParameters {
    val taskPath: Property<String>
    val workerKey: Property<String>
}