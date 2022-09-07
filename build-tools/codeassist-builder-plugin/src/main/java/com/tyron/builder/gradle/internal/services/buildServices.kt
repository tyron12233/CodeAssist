package com.tyron.builder.gradle.internal.services

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceRegistry
import java.util.*

/** Registers and configures the build service with the specified type. */
abstract class ServiceRegistrationAction<ServiceT, ParamsT>(
    protected val project: Project,
    private val buildServiceClass: Class<ServiceT>,
    private val maxParallelUsages: Int? = null,
    private val name: String = getBuildServiceName(buildServiceClass),
) where ServiceT : BuildService<ParamsT>, ParamsT : BuildServiceParameters {
    open fun execute(): Provider<ServiceT> {
        return project.gradle.sharedServices.registerIfAbsent(
            name,
            buildServiceClass
        ) { buildServiceSpec ->
            buildServiceSpec.parameters?.let { params -> configure(params) }
            maxParallelUsages?.let { buildServiceSpec.maxParallelUsages.set(it) }
        }
    }

    abstract fun configure(parameters: ParamsT)
}

/** Returns the build service of [ServiceT] type. */
inline fun <reified ServiceT : BuildService<ParamsT>, ParamsT: BuildServiceParameters> getBuildService(buildServiceRegistry: BuildServiceRegistry): Provider<ServiceT> {
    return getBuildService(buildServiceRegistry, ServiceT::class.java)
}

/** Returns the build service with the specified type. Prefer reified [getBuildService] to this method. */
fun <ServiceT : BuildService<ParamsT>, ParamsT: BuildServiceParameters> getBuildService(
    buildServiceRegistry: BuildServiceRegistry,
    buildServiceClass: Class<ServiceT>
): Provider<ServiceT> {
    val serviceName = getBuildServiceName(buildServiceClass)
    /**
     * We use registerIfAbsent in order to ensure locking when accessing build services. Because of
     * https://github.com/gradle/gradle/issues/18587, Gradle ensures thread safety only for
     * service registration. Using [BuildServiceRegistry.getRegistrations] to access build services
     * may cause problems such as http://b/238336467.
     */
    return buildServiceRegistry.registerIfAbsent(serviceName, buildServiceClass) {
        throw IllegalStateException("Service $serviceName is not registered.")
    }
}

/**
 * Get build service name that works even if build service types come from different class loaders.
 * If the service name is the same, and some type T is defined in two class loaders L1 and L2. E.g.
 * this is true for composite builds and other project setups (see b/154388196).
 *
 * Registration of service may register (T from L1) or (T from L2). This means that querying it with
 * T from other class loader will fail at runtime. This method makes sure both T from L1 and T from
 * L2 will successfully register build services.
 */
fun getBuildServiceName(type: Class<*>): String = type.name + "_" + perClassLoaderConstant

/** Used to get unique build service name. Each class loader will initialize its own version. */
private val perClassLoaderConstant = UUID.randomUUID().toString()