package com.tyron.builder.configurationcache;

import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildToolingModelController;
import com.tyron.builder.internal.build.BuildToolingModelControllerFactory;
import com.tyron.builder.internal.build.DefaultBuildToolingModelController;
import com.tyron.builder.internal.buildtree.BuildModelParameters;

public class DefaultBuildToolingModelControllerFactory implements BuildToolingModelControllerFactory {

    private final BuildModelParameters modelParameters;

    public DefaultBuildToolingModelControllerFactory(BuildModelParameters modelParameters) {
        this.modelParameters = modelParameters;
    }

    @Override
    public BuildToolingModelController createController(BuildState owner,
                                                        BuildLifecycleController controller) {
        return new DefaultBuildToolingModelController(owner, controller);
    }
}
