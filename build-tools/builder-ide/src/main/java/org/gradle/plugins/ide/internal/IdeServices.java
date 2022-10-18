package org.gradle.plugins.ide.internal;

import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;

public class IdeServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.add(IdeArtifactStore.class);
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.add(DefaultIdeArtifactRegistry.class);
    }
}