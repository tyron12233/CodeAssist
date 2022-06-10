package org.gradle.internal.service.scopes;

import org.gradle.internal.service.ServiceRegistration;

public class ExecutionServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new ExecutionGlobalServices());
    }

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new ExecutionGradleServices());
    }
}
