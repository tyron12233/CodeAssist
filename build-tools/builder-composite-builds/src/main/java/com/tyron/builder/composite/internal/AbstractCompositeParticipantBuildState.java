package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.artifacts.DefaultModuleVersionIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultProjectComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ForeignBuildIdentifier;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.build.AbstractBuildState;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.CompositeBuildParticipantBuildState;
import com.tyron.builder.internal.buildtree.BuildTreeState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

public abstract class AbstractCompositeParticipantBuildState extends AbstractBuildState implements CompositeBuildParticipantBuildState {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCompositeParticipantBuildState.class);

    private Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> availableModules;

    public AbstractCompositeParticipantBuildState(BuildTreeState buildTree, BuildDefinition buildDefinition, @Nullable BuildState parent) {
        super(buildTree, buildDefinition, parent);
    }

    @Override
    public synchronized Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> getAvailableModules() {
        if (availableModules == null) {
            ensureChildBuildConfigured();
            availableModules = new LinkedHashSet<>();
            for (ProjectStateUnk project : getProjects().getAllProjects()) {
                registerProject(availableModules, project.getMutableModel());
            }
        }
        return availableModules;
    }

    protected void ensureChildBuildConfigured() {
        ensureProjectsConfigured();
    }

    private void registerProject(Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> availableModules, ProjectInternal project) {
        ProjectComponentIdentifier projectIdentifier = new DefaultProjectComponentIdentifier(getBuildIdentifier(), project.getIdentityPath(), project.getProjectPath(), project.getName());
        ModuleVersionIdentifier moduleId = DefaultModuleVersionIdentifier
                .newId(project.getDependencyMetaDataProvider().getModule());
        LOGGER.info("Registering {} in composite build. Will substitute for module '{}'.", project, moduleId.getModule());
        availableModules.add(Pair.of(moduleId, projectIdentifier));
    }

    @Override
    public ProjectComponentIdentifier idToReferenceProjectFromAnotherBuild(ProjectComponentIdentifier identifier) {
        // Need to use a 'foreign' build id to make BuildIdentifier.isCurrentBuild and BuildIdentifier.name work in dependency results
        DefaultProjectComponentIdentifier original = (DefaultProjectComponentIdentifier) identifier;
        String name = getIdentityPath().getName();
        if (name == null) {
            name = getBuildIdentifier().getName();
        }
        return new DefaultProjectComponentIdentifier(new ForeignBuildIdentifier(getBuildIdentifier().getName(), name), original.getIdentityPath(), original.projectPath(), original.getProjectName());
    }
}