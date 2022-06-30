package com.tyron.builder.api.internal.artifacts.dependencies;

import com.google.common.base.Objects;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ProjectDependency;
import com.tyron.builder.api.internal.artifacts.CachingDependencyResolveContext;
import com.tyron.builder.api.internal.artifacts.DependencyResolveContext;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.tasks.AbstractTaskDependency;
import com.tyron.builder.api.internal.tasks.TaskDependencyInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.internal.deprecation.DeprecationMessageBuilder;
import com.tyron.builder.internal.exceptions.ConfigurationNotConsumableException;
import com.tyron.builder.util.internal.GUtil;

import java.io.File;
import java.util.Collections;
import java.util.Set;

public class DefaultProjectDependency extends AbstractModuleDependency implements ProjectDependencyInternal {
    private final ProjectInternal dependencyProject;
    private final boolean buildProjectDependencies;

    public DefaultProjectDependency(ProjectInternal dependencyProject, boolean buildProjectDependencies) {
        this(dependencyProject, null, buildProjectDependencies);
    }

    public DefaultProjectDependency(ProjectInternal dependencyProject, String configuration, boolean buildProjectDependencies) {
        super(configuration);
        this.dependencyProject = dependencyProject;
        this.buildProjectDependencies = buildProjectDependencies;
    }

    @Override
    public BuildProject getDependencyProject() {
        return dependencyProject;
    }

    @Override
    public String getGroup() {
        return dependencyProject.getGroup().toString();
    }

    @Override
    public String getName() {
        return dependencyProject.getName();
    }

    @Override
    public String getVersion() {
        return dependencyProject.getVersion().toString();
    }

    @Override
    public Configuration findProjectConfiguration() {
        ConfigurationContainer dependencyConfigurations = getDependencyProject().getConfigurations();
        String declaredConfiguration = getTargetConfiguration();
        Configuration selectedConfiguration = dependencyConfigurations.getByName(GUtil.elvis(declaredConfiguration, Dependency.DEFAULT_CONFIGURATION));
        if (!selectedConfiguration.isCanBeConsumed()) {
            throw new ConfigurationNotConsumableException(dependencyProject.getDisplayName(), selectedConfiguration.getName());
        }
        warnIfConfigurationIsDeprecated((DeprecatableConfiguration) selectedConfiguration);
        return selectedConfiguration;
    }

    private void warnIfConfigurationIsDeprecated(DeprecatableConfiguration selectedConfiguration) {
        DeprecationMessageBuilder.WithDocumentation consumptionDeprecation = selectedConfiguration.getConsumptionDeprecation();
        if (consumptionDeprecation != null) {
            consumptionDeprecation.nagUser();
        }
    }

    @Override
    public ProjectDependency copy() {
        DefaultProjectDependency copiedProjectDependency = new DefaultProjectDependency(dependencyProject, getTargetConfiguration(), buildProjectDependencies);
        copyTo(copiedProjectDependency);
        return copiedProjectDependency;
    }

    @Override
    public Set<File> resolve() {
        return resolve(true);
    }

    @Override
    public Set<File> resolve(boolean transitive) {
        CachingDependencyResolveContext context = new CachingDependencyResolveContext(transitive, Collections.emptyMap());
        context.add(this);
        return context.resolve().getFiles();
    }

    @Override
    public void resolve(DependencyResolveContext context) {
        boolean transitive = isTransitive() && context.isTransitive();
        if (transitive) {
            Configuration projectConfiguration = findProjectConfiguration();
            for (Dependency dependency : projectConfiguration.getAllDependencies()) {
                context.add(dependency);
            }
            for (DependencyConstraint dependencyConstraint : projectConfiguration.getAllDependencyConstraints()) {
                context.add(dependencyConstraint);
            }
        }
    }

    @Override
    public TaskDependencyInternal getBuildDependencies() {
        return new TaskDependencyImpl();
    }

    @Override
    public boolean contentEquals(Dependency dependency) {
        if (this == dependency) {
            return true;
        }
        if (dependency == null || getClass() != dependency.getClass()) {
            return false;
        }

        ProjectDependency that = (ProjectDependency) dependency;
        if (!isCommonContentEquals(that)) {
            return false;
        }

        return dependencyProject.equals(that.getDependencyProject());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultProjectDependency that = (DefaultProjectDependency) o;
        if (!this.getDependencyProject().equals(that.getDependencyProject())) {
            return false;
        }
        if (getTargetConfiguration() != null ? !this.getTargetConfiguration().equals(that.getTargetConfiguration())
            : that.getTargetConfiguration() != null) {
            return false;
        }
        if (this.buildProjectDependencies != that.buildProjectDependencies) {
            return false;
        }
        if (!Objects.equal(getAttributes(), that.getAttributes())) {
            return false;
        }
        if (!Objects.equal(getRequestedCapabilities(), that.getRequestedCapabilities())) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return getDependencyProject().hashCode() ^ (getTargetConfiguration() != null ? getTargetConfiguration().hashCode() : 31) ^ (buildProjectDependencies ? 1 : 0);
    }

    @Override
    public String toString() {
        return "DefaultProjectDependency{" + "dependencyProject='" + dependencyProject + '\'' + ", configuration='"
            + (getTargetConfiguration() == null ? Dependency.DEFAULT_CONFIGURATION : getTargetConfiguration()) + '\'' + '}';
    }

    private class TaskDependencyImpl extends AbstractTaskDependency {
        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            if (!buildProjectDependencies) {
                return;
            }

            Configuration configuration = findProjectConfiguration();
            context.add(configuration);
            context.add(configuration.getAllArtifacts());
        }
    }

}
