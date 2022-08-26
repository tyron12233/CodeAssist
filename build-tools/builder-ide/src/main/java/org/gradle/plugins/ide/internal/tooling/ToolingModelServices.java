package org.gradle.plugins.ide.internal.tooling;

import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.project.ProjectTaskLister;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.plugins.ide.internal.configurer.DefaultUniqueProjectNameProvider;
import org.gradle.plugins.ide.internal.configurer.UniqueProjectNameProvider;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;
import org.gradle.tooling.provider.model.internal.BuildScopeToolingModelBuilderRegistryAction;
import org.jetbrains.annotations.NotNull;

public class ToolingModelServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new BuildScopeToolingServices());
    }

    private static class BuildScopeToolingServices {

        protected UniqueProjectNameProvider createBuildProjectRegistry(ProjectStateRegistry projectRegistry) {
            return new DefaultUniqueProjectNameProvider(projectRegistry);
        }

        protected BuildScopeToolingModelBuilderRegistryAction createIdeBuildScopeToolingModelBuilderRegistryAction(
            final ProjectTaskLister taskLister,
            final ProjectPublicationRegistry projectPublicationRegistry,
            final FileCollectionFactory fileCollectionFactory,
            final BuildStateRegistry buildStateRegistry,
            final ProjectStateRegistry projectStateRegistry
        ) {

            return new BuildScopeToolingModelBuilderRegistryAction() {
                @Override
                public void execute(@NotNull ToolingModelBuilderRegistry registry) {
                    GradleProjectBuilder gradleProjectBuilder = new GradleProjectBuilder();
                    IdeaModelBuilder ideaModelBuilder = new IdeaModelBuilder(gradleProjectBuilder);
//                    registry.register(new RunBuildDependenciesTaskBuilder());
//                    registry.register(new RunEclipseTasksBuilder());
//                    registry.register(new EclipseModelBuilder(gradleProjectBuilder, projectStateRegistry));
                    registry.register(ideaModelBuilder);
                    registry.register(gradleProjectBuilder);
                    registry.register(new GradleBuildBuilder(buildStateRegistry));
                    registry.register(new BasicIdeaModelBuilder(ideaModelBuilder));
                    registry.register(new BuildInvocationsBuilder(taskLister));
                    registry.register(new PublicationsBuilder(projectPublicationRegistry));
                    registry.register(new BuildEnvironmentBuilder(fileCollectionFactory));
                }
            };
        }
    }
}
