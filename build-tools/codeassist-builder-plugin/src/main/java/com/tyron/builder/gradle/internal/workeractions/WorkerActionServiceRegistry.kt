package com.tyron.builder.gradle.internal.workeractions

import java.io.Closeable
import java.io.Serializable

/**
 * Static singleton manager of services "injected" in to worker actions.
 *
 * See implementations of [ServiceKey] to find services.
 */
class WorkerActionServiceRegistry {
    interface ServiceKey<T : Any>: Serializable {
        val type: Class<T>
    }

    interface RegisteredService<out T : Any> {
        val service: T
        fun shutdown()
    }

    private val services: MutableMap<ServiceKey<*>, RegisteredService<*>> = mutableMapOf()

    /**
     * Registers a service that can be retrieved by use of the service key. [Closeable] is returned
     * which should be used to managed the service lifecycle in the registry. Once [Closeable.close]
     * is invoked, service is removed.
     *
     * If service with the same key is already registered, old service is not overwritten. Also,
     * invoker will receive a no-op [Closeable]. This means that it is responsibility of the invoker
     * that registers the service to also remove it.
     *
     * If service is null, service registration is ignored, and a no-op [Closeable] is returned.
     *
     * A note about using this method in tasks with worker actions. If try-with-resources is used,
     * users of this API should make sure [Closeable.close] is not invoked before all build
     * operations that require the registered service complete. If task action does not wait for
     * the worker actions to finish, using gradle build services is a better fit.
     */
    @Synchronized
    fun <T : Any> registerServiceAsCloseable(key: ServiceKey<T>, service: T?): Closeable {
        if (key in services || service == null) return Closeable { }

        services[key] = object : RegisteredService<T> {
            override val service: T = service

            override fun shutdown() {}
        }
        return Closeable { synchronized(this@WorkerActionServiceRegistry) { services.remove(key) } }
    }

    /** Get a previously registered service */
    @Synchronized
    fun <T : Any> getService(key: ServiceKey<T>): RegisteredService<T> {
        @Suppress("UNCHECKED_CAST") // Type matched when stored in service map.
        return services[key] as RegisteredService<T>? ?: serviceNotFoundError(key)
    }

    private fun serviceNotFoundError(key: ServiceKey<*>): Nothing {
        if (services.isEmpty()) {
            throw IllegalStateException("No services are registered. " +
                    "Ensure the worker actions use IsolationMode.NONE.")
        }
        throw IllegalStateException(
                "Service $key not registered. Available services: " +
                        "[${services.keys.joinToString(separator = ", ")}].")
    }
}