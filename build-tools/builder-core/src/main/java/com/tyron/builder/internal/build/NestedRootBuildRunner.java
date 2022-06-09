package com.tyron.builder.internal.build;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.service.ServiceRegistry;

import javax.annotation.Nullable;

public class NestedRootBuildRunner {

    public static StartParameter createStartParameterForNewBuild(ServiceRegistry services) {
        return services.get(StartParameter.class).newBuild();
    }

    public static void runNestedRootBuild(String buildName, StartParameterInternal startParameter, ServiceRegistry services) {
        createNestedBuildTree(buildName, startParameter, services).run(buildController -> {
            buildController.scheduleAndRunTasks();
            return null;
        });
    }

    public static NestedBuildTree createNestedBuildTree(@Nullable String buildName, StartParameterInternal startParameter, ServiceRegistry services) {
        PublicBuildPath fromBuild = services.get(PublicBuildPath.class);
        BuildDefinition buildDefinition = BuildDefinition.fromStartParameter(startParameter, fromBuild);

        BuildState currentBuild = services.get(BuildState.class);

        BuildStateRegistry buildStateRegistry = services.get(BuildStateRegistry.class);
        return buildStateRegistry.addNestedBuildTree(buildDefinition, currentBuild, buildName);
    }
}