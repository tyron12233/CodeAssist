package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.service.ServiceRegistry;

import java.io.File;
import java.util.Optional;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Manages the shared services that are scoped to a particular Gradle user home dir and which can be shared by multiple build invocations. These shared services also include the global services.
 *
 * <p>A plugin can contribute shared services to this scope by providing an implementation of {@link PluginServiceRegistry}.
 */
@ThreadSafe
public interface GradleUserHomeScopeServiceRegistry {
    /**
     * Locates the shared services to use for the given Gradle user home dir. The returned registry also includes global services.
     *
     * <p>The caller is responsible for releasing the registry when it is finished using it by calling {@link #release(ServiceRegistry)}.</p>
     */
    ServiceRegistry getServicesFor(File gradleUserHomeDir);

    /**
     * Locates the shared services from the last used user home dir - if any.
     *
     * The method will never create services, so the caller should not release the registry.
     */
    Optional<ServiceRegistry> getCurrentServices();

    /**
     * Releases a service registry created by {@link #getServicesFor(File)}.
     */
    void release(ServiceRegistry services);
}