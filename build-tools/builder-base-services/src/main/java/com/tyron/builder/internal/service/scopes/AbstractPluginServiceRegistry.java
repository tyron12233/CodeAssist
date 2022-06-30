package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.service.ServiceRegistration;

/**
 * Base no-op implementation of the {@link PluginServiceRegistry}.
 */
public class AbstractPluginServiceRegistry implements PluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {

    }

    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {

    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {

    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {

    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {

    }

    @Override
    public void registerSettingsServices(ServiceRegistration registration) {

    }

    @Override
    public void registerGradleServices(ServiceRegistration registration) {

    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {

    }
}
