package com.tyron.builder.gradle.internal.tasks.factory

import org.gradle.api.component.SoftwareComponentFactory

interface TaskManagerConfig {

    val componentFactory: SoftwareComponentFactory

    val dataBindingBuilder: Any
}