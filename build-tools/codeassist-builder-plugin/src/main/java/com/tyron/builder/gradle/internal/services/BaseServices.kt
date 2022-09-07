package com.tyron.builder.gradle.internal.services

import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.errors.DeprecationReporter
import com.tyron.builder.gradle.internal.scope.ProjectInfo
import com.tyron.builder.gradle.internal.utils.GradleEnvironmentProvider
import com.tyron.builder.gradle.options.ProjectOptions
import org.gradle.api.services.BuildServiceRegistry
import java.io.File

/**
 * Interface providing services useful everywhere.
 */
interface BaseServices {

    val issueReporter: IssueReporter
    val deprecationReporter: DeprecationReporter
    val projectOptions: ProjectOptions
    val buildServiceRegistry: BuildServiceRegistry
    val gradleEnvironmentProvider: GradleEnvironmentProvider
    val projectInfo: ProjectInfo

    fun <T> newInstance(type: Class<T>, vararg args: Any?): T

    fun file(file: Any): File
}