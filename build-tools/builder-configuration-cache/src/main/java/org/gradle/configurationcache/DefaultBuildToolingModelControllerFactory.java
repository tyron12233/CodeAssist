package org.gradle.configurationcache;

import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildToolingModelController;
import org.gradle.internal.build.BuildToolingModelControllerFactory;
import org.gradle.internal.build.DefaultBuildToolingModelController;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.tooling.provider.model.internal.ToolingModelBuilderLookup;

public class DefaultBuildToolingModelControllerFactory implements BuildToolingModelControllerFactory {

    private final BuildModelParameters modelParameters;

    public DefaultBuildToolingModelControllerFactory(BuildModelParameters modelParameters) {
        this.modelParameters = modelParameters;
    }

    @Override
    public BuildToolingModelController createController(BuildState owner,
                                                        BuildLifecycleController controller) {
        if (modelParameters.isIntermediateModelCache()) {
            throw new UnsupportedOperationException();
        }
        return new DefaultBuildToolingModelController(owner, controller,  controller.getGradle().getServices().get(
                ToolingModelBuilderLookup.class));
    }
}
