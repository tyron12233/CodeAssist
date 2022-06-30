package com.tyron.builder.configurationcache;

import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;

public class ConfigurationCacheServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
//        registration.add(ConfigurationCacheKey.class);
        registration.add(DefaultBuildToolingModelControllerFactory.class);
        registration.add(DefaultBuildModelControllerServices.class);
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.add(DefaultBuildTreeControllerServices.class);
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        
    }
}
