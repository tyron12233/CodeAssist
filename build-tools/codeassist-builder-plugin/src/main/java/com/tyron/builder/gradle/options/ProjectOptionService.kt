package com.tyron.builder.gradle.options

import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import com.tyron.builder.gradle.internal.utils.toImmutableMap
import org.gradle.api.Project
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import javax.inject.Inject

/**
 * A build service to provide [ProjectOptions] to all projects.
 */
abstract class ProjectOptionService : BuildService<ProjectOptionService.Params> {

    /**
     * Custom test runner arguments can only be obtained with [Project] instance, so we need to
     * capture them in first non configuration cached run and reuse them in configuration cached
     * runs.
     *
     * Adding custom runner arguments from gradle.properties is not fully compatible with config
     * caching because we would miss newly added arguments in configuration cached runs. Therefore,
     * we raise warnings when finding custom test runner arguments in gradle.properties and
     * encourage users to add those arguments in gradle dsl.
     */
    interface Params : BuildServiceParameters {
        val customTestRunnerArgs: MapProperty<String, String>
    }

    @get:Inject
    abstract val providerFactory: ProviderFactory

    val projectOptions: ProjectOptions =
        ProjectOptions(parameters.customTestRunnerArgs.get().toImmutableMap(), providerFactory)

    class RegistrationAction(project: Project)
        : ServiceRegistrationAction<ProjectOptionService, Params>(
        project,
        ProjectOptionService::class.java
    ) {
        override fun configure(parameters: Params) {
//            val standardArgs = TestRunnerArguments.values().map {
//                it.toString().lowercase(Locale.US)
//            }
            val customArgs = mutableMapOf<String, String>()
            project.extensions.extraProperties.properties.entries.forEach {
//                if (it.key.startsWith(TEST_RUNNER_ARGS_PREFIX)) {
//                    val argName = it.key.substring(TEST_RUNNER_ARGS_PREFIX.length)
//                    if (standardArgs.contains(argName)) {
//                        return@forEach
//                    }
//                    // As we would ignore new custom arguments added as gradle properties in
//                    // the following configuration-cached runs, we need to encourage users to
//                    // specify custom arguments through dsl.
//                    project.logger.warn(
//                        "Passing custom test runner argument ${it.key} from gradle.properties"
//                                + " or command line is not compatible with configuration caching. "
//                                + "Please specify this argument using android gradle dsl."
//                    )
//                    val argValue = it.value.toString();
//                    customArgs[argName] = argValue
//                    // Make sure we invalidate configuration cache if existing custom arguments change
//                    project.property(it.key)
//                }
            }
            parameters.customTestRunnerArgs.set(customArgs)
        }
    }
}
