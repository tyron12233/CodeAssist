package org.gradle.internal.service.scopes;


import org.gradle.execution.plan.DefaultPlanExecutor;
import org.gradle.internal.service.ServiceRegistration;

public class ExecutionServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new ExecutionGlobalServices());
    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.add(DefaultPlanExecutor.class);
    }

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new ExecutionGradleServices());
    }
}