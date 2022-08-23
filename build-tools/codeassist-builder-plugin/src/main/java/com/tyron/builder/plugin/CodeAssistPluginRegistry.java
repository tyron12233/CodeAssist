package com.tyron.builder.plugin;

import org.gradle.api.internal.component.ComponentTypeRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;

public class CodeAssistPluginRegistry extends AbstractPluginServiceRegistry {

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        registration.addProvider(new GradleScopeServices());
    }

    public static class GradleScopeServices {
        public void configure(ServiceRegistration registration, ComponentTypeRegistry componentTypeRegistry) {

        }
    }
}
