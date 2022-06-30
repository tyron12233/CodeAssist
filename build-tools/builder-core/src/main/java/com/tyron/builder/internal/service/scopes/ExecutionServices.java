package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.service.ServiceRegistration;

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
