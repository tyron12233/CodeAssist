package org.gradle.plugins.ide.internal.tooling;

import com.google.common.collect.Lists;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectComponentPublication;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.plugins.ide.internal.tooling.model.DefaultGradleModuleVersion;
import org.gradle.plugins.ide.internal.tooling.model.DefaultGradlePublication;
import org.gradle.plugins.ide.internal.tooling.model.DefaultProjectPublications;
import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

import java.util.List;

class PublicationsBuilder implements ToolingModelBuilder {
    private final ProjectPublicationRegistry publicationRegistry;

    PublicationsBuilder(ProjectPublicationRegistry publicationRegistry) {
        this.publicationRegistry = publicationRegistry;
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.gradle.ProjectPublications");
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        DefaultProjectIdentifier projectIdentifier = new DefaultProjectIdentifier(project.getRootDir(), project.getPath());
        return new DefaultProjectPublications().setPublications(publications((ProjectInternal) project, projectIdentifier)).setProjectIdentifier(projectIdentifier);
    }

    private List<DefaultGradlePublication> publications(ProjectInternal project, DefaultProjectIdentifier projectIdentifier) {
        List<DefaultGradlePublication> gradlePublications = Lists.newArrayList();

        for (ProjectComponentPublication projectPublication : publicationRegistry.getPublications(ProjectComponentPublication.class, project.getIdentityPath())) {
            ModuleVersionIdentifier id = projectPublication.getCoordinates(ModuleVersionIdentifier.class);
            if (id != null) {
                gradlePublications.add(new DefaultGradlePublication()
                        .setId(new DefaultGradleModuleVersion(id))
                        .setProjectIdentifier(projectIdentifier)
                );
            }
        }

        return gradlePublications;
    }
}
