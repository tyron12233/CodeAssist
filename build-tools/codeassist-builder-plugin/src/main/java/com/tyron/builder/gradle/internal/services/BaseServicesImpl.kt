package com.tyron.builder.gradle.internal.services

import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.errors.DeprecationReporter
import com.tyron.builder.gradle.internal.scope.ProjectInfo
import com.tyron.builder.gradle.internal.utils.GradleEnvironmentProvider
import com.tyron.builder.gradle.internal.utils.GradleEnvironmentProviderImpl
import com.tyron.builder.gradle.options.ProjectOptions
import org.gradle.api.services.BuildServiceRegistry
import java.io.File

/**
 * Impl for BaseScope over a [ProjectServices]
 */
open class BaseServicesImpl(protected val projectServices: ProjectServices):
    BaseServices {

    final override fun <T> newInstance(type: Class<T>, vararg args: Any?): T = projectServices.objectFactory.newInstance(type, *args)

    final override fun file(file: Any): File = projectServices.fileResolver.invoke(file)

    final override val issueReporter: IssueReporter
        get() = projectServices.issueReporter
    final override val deprecationReporter: DeprecationReporter
        get() = projectServices.deprecationReporter
    final override val projectOptions: ProjectOptions
        get() = projectServices.projectOptions
    final override val buildServiceRegistry: BuildServiceRegistry
        get() = projectServices.buildServiceRegistry

    final override val gradleEnvironmentProvider: GradleEnvironmentProvider =
        GradleEnvironmentProviderImpl(projectServices.providerFactory)

    final override val projectInfo: ProjectInfo
        get() = projectServices.projectInfo
}