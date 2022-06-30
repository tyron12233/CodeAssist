package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistryBuilder;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;

import java.io.Closeable;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reuses the services for the most recent Gradle user home dir. Could instead cache several most recent and clean these up on memory pressure, however in practise there is only a single user home dir associated with a given build process.
 */
public class DefaultGradleUserHomeScopeServiceRegistry implements GradleUserHomeScopeServiceRegistry, Closeable {
    public static final String REUSE_USER_HOME_SERVICES = "org.gradle.internal.reuse.user.home.services";
    private final ServiceRegistry sharedServices;
    private final Object provider;
    private final Lock lock = new ReentrantLock();
    private final Map<File, Services> servicesForHomeDir = new HashMap<>();

    public DefaultGradleUserHomeScopeServiceRegistry(ServiceRegistry sharedServices, Object provider) {
        this.sharedServices = sharedServices;
        this.provider = provider;
    }

    @Override
    public void close() {
        CompositeStoppable stoppable = new CompositeStoppable();
        lock.lock();
        try {
            for (Map.Entry<File, Services> entry : servicesForHomeDir.entrySet()) {
                Services services = entry.getValue();
                if (services.count != 0) {
                    throw new IllegalStateException("Services for Gradle user home directory '" + entry.getKey() + "' have not been released.");
                }
                stoppable.add(services.registry);
            }
            servicesForHomeDir.clear();
        } finally {
            lock.unlock();
        }
        stoppable.stop();
    }

    @Override
    public ServiceRegistry getServicesFor(final File gradleUserHomeDir) {
        lock.lock();
        try {
            Services services = servicesForHomeDir.get(gradleUserHomeDir);
            if (services == null) {
                if (servicesForHomeDir.size() == 1) {
                    Services otherServices = servicesForHomeDir.values().iterator().next();
                    if (otherServices.count == 0) {
                        // Other home dir cached and not in use, clean it up
                        CompositeStoppable.stoppable(otherServices.registry).stop();
                        servicesForHomeDir.clear();
                    }
                }
                ServiceRegistry userHomeServices = ServiceRegistryBuilder.builder()
                        .parent(sharedServices)
                        .displayName("services for Gradle user home dir " + gradleUserHomeDir)
                        .provider(new Object() {
                            GradleUserHomeDirProvider createGradleUserHomeDirProvider() {
                                return () -> gradleUserHomeDir;
                            }
                        })
                        .provider(provider)
                        .build();
                services = new Services(userHomeServices);
                servicesForHomeDir.put(gradleUserHomeDir, services);
            }
            services.count++;
            return services.registry;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ServiceRegistry> getCurrentServices() {
        lock.lock();
        try {
            return servicesForHomeDir.isEmpty()
                    ? Optional.empty()
                    : Optional.of(servicesForHomeDir.values().iterator().next().registry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(ServiceRegistry registry) {
        lock.lock();
        try {
            for (Map.Entry<File, Services> entry : servicesForHomeDir.entrySet()) {
                Services services = entry.getValue();
                if (services.registry == registry) {
                    if (services.count <= 0) {
                        break;
                    }
                    services.count--;
                    if (services.count == 0 && (servicesForHomeDir.size() > 1 || System.getProperty(REUSE_USER_HOME_SERVICES, "true").equals("false"))) {
                        // Other home dir in use, close these. Otherwise, keep the services for next time
                        CompositeStoppable.stoppable(services.registry).stop();
                        servicesForHomeDir.remove(entry.getKey());
                    }
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
        throw new IllegalStateException("Gradle user home directory scoped services have already been released.");
    }

    private static class Services {
        private final ServiceRegistry registry;
        private int count;

        public Services(ServiceRegistry registry) {
            this.registry = registry;
        }
    }
}