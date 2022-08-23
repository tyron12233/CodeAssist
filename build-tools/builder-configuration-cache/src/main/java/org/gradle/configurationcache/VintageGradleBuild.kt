package org.gradle.configurationcache

import org.gradle.api.internal.GradleInternal
import org.gradle.execution.plan.Node


interface VintageGradleBuild {
    val gradle: GradleInternal
    val scheduledWork: List<Node>
}
