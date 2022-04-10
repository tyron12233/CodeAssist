package com.tyron.builder.configurationcache;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.internal.BuildType;
import com.tyron.builder.internal.buildTree.BuildActionModelRequirements;
import com.tyron.builder.internal.buildTree.BuildModelParameters;
import com.tyron.builder.internal.buildTree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildTree.RunTasksRequirements;

public class DefaultBuildTreeControllerServices implements BuildTreeModelControllerServices {
    @Override
    public Supplier servicesForBuildTree(BuildActionModelRequirements requirements) {
        StartParameterInternal startParameter = requirements.getStartParameter();

        // Isolated projects also implies configuration cache
        if (startParameter.getIsolatedProjects().get() && !startParameter.getConfigurationCache().get()) {
            if (startParameter.getConfigurationCache().isExplicit()) {
                throw new BuildException("The configuration cache cannot be disabled when isolated projects is enabled.");
            }
        }

//        Boolean isolatedProjects = startParameter.getIsolatedProjects().get();
        return registration -> {
            registration.add(BuildType.class, BuildType.TASKS);
            BuildModelParameters buildModelParameters =
                    new BuildModelParameters(false, false, false, true, false, false, false);
            registerServices(registration, buildModelParameters, new RunTasksRequirements(startParameter));
        };
    }

    private void registerServices(ServiceRegistration registration,
                                  BuildModelParameters modelParameters,
                                  BuildActionModelRequirements requirements) {
        registration.add(BuildModelParameters.class, modelParameters);
        registration.add(BuildActionModelRequirements.class, requirements);
        if (modelParameters.isConfigurationCache()) {

        }
    }

    @Override
    public Supplier servicesForNestedBuildTree(StartParameterInternal startParameter) {
        return null;
    }
}
