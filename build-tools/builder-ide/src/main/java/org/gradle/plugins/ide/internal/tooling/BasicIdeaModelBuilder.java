package org.gradle.plugins.ide.internal.tooling;

import org.gradle.api.Project;
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaProject;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

public class BasicIdeaModelBuilder implements ToolingModelBuilder {
    private final IdeaModelBuilder ideaModelBuilder;

    public BasicIdeaModelBuilder(IdeaModelBuilder ideaModelBuilder) {
        this.ideaModelBuilder = ideaModelBuilder;
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.idea.BasicIdeaProject");
    }

    @Override
    public DefaultIdeaProject buildAll(String modelName, Project project) {
        return ideaModelBuilder
                .setOfflineDependencyResolution(true)
                .buildAll(modelName, project);
    }
}