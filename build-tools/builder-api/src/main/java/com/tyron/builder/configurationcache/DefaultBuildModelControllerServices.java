package com.tyron.builder.configurationcache;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildState;

import javax.annotation.Nullable;

public class DefaultBuildModelControllerServices implements BuildModelControllerServices {
    @Override
    public Supplier servicesForBuild(BuildDefinition buildDefinition,
                                     BuildState owner,
                                     @Nullable BuildState parentBuild) {
        return (registration, services) -> {
            registration.add(BuildDefinition.class, buildDefinition);
            registration.add(BuildState.class, owner);
            registration.addProvider(new ServicesProvider(buildDefinition, parentBuild, services));
        };
    }

    private class ServicesProvider {
        private final BuildDefinition buildDefinition;
        private final BuildState parentBuild;
        private final BuildScopeServices services;

        public ServicesProvider(BuildDefinition buildDefinition,
                                BuildState parentBuild,
                                BuildScopeServices services) {

            this.buildDefinition = buildDefinition;
            this.parentBuild = parentBuild;
            this.services = services;
        }

        GradleInternal createGradleModel(ServiceRegistryFactory registryFactory) {
            return new DefaultGradle(parentBuild, buildDefinition.getStartParameter(), registryFactory);
        }
    }
}
