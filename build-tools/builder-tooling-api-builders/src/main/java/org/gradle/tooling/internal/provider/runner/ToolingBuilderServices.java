package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;

public class ToolingBuilderServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.add(ToolingApiBuildEventListenerFactory.class);
    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.add(BuildControllerFactory.class);
        registration.add(BuildModelActionRunner.class);
        registration.add(TestExecutionRequestActionRunner.class);
        registration.add(ClientProvidedBuildActionRunner.class);
        registration.add(ClientProvidedPhasedActionRunner.class);
    }
}