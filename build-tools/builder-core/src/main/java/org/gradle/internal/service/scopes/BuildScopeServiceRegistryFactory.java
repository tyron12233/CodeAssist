package org.gradle.internal.service.scopes;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.service.ServiceRegistry;

import java.io.Closeable;

public class BuildScopeServiceRegistryFactory implements ServiceRegistryFactory, Closeable {

    private final ServiceRegistry services;
    private final CompositeStoppable registries = new CompositeStoppable();

    public BuildScopeServiceRegistryFactory(ServiceRegistry services) {
        this.services = services;
    }

    @Override
    public ServiceRegistry createFor(Object domainObject) {
        if (domainObject instanceof GradleInternal) {
            GradleScopeServices gradleServices = new GradleScopeServices(services);
            registries.add(gradleServices);
            return gradleServices;
        }
        if (domainObject instanceof SettingsInternal) {
            SettingsScopeServices settingsServices = new SettingsScopeServices(services, (SettingsInternal) domainObject);
            registries.add(settingsServices);
            return settingsServices;
        }
        throw new IllegalArgumentException(String.format("Cannot create services for unknown domain object of type %s.", domainObject.getClass().getSimpleName()));
    }

    @Override
    public void close() {
        registries.stop();
    }
}
