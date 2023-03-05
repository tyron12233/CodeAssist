package com.tyron.builder.gradle.internal.services

import com.android.prefs.AbstractAndroidLocations
import com.android.prefs.AndroidLocationsProvider
import com.android.utils.ILogger
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.services.AndroidLocationsBuildService.AndroidLocations
import com.tyron.builder.gradle.internal.utils.EnvironmentProviderImpl
import com.tyron.builder.gradle.internal.utils.GradleEnvironmentProviderImpl
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.file.Path
import javax.inject.Inject

/**
 * A build service around [AndroidLocations] in order to make this basically a singleton
 * while not using static fields.
 *
 * Using static fields (like we used to do) means that the cached value is tied to the loaded
 * class and can survive across different builds, even if the computed value would have changed
 * (due to injected environment value for instance), as long as the gradle daemon is re-used.
 */
abstract class AndroidLocationsBuildService @Inject constructor(
    providerFactory: ProviderFactory
) : BuildService<BuildServiceParameters.None>, AutoCloseable, AndroidLocationsProvider {

     // ----- AndroidLocationsProvider -----

    override val prefsLocation: Path
        get() = androidLocations.prefsLocation

    override val avdLocation: Path
        get() = androidLocations.avdLocation

    override val gradleAvdLocation: Path
        get() = androidLocations.gradleAvdLocation

    override val userHomeLocation: Path
        get() = androidLocations.userHomeLocation

    // -----

    override fun close() {
        // nothing to be done here
    }

    private val androidLocations = AndroidLocations(
        EnvironmentProviderImpl(GradleEnvironmentProviderImpl(providerFactory)),
        LoggerWrapper(Logging.getLogger("AndroidLocations"))
    )

    class RegistrationAction(
        project: Project
    ) : ServiceRegistrationAction<AndroidLocationsBuildService, BuildServiceParameters.None>(
        project,
        AndroidLocationsBuildService::class.java
    ) {
        override fun configure(parameters: BuildServiceParameters.None) {
        }
    }

    /**
     * Implementation of [AbstractAndroidLocations] for usage inside the build services
     */
    private class AndroidLocations(
        environmentProvider: EnvironmentProviderImpl,
        logger: ILogger
    ): AbstractAndroidLocations(environmentProvider, logger, silent = true)
}
