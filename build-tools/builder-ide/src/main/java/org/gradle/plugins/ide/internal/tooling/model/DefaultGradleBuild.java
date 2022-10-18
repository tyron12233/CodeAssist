package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.internal.gradle.DefaultBuildIdentifier;
import org.gradle.tooling.internal.gradle.GradleBuildIdentity;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class DefaultGradleBuild implements Serializable, GradleBuildIdentity {
    private PartialBasicGradleProject rootProject;
    private DefaultBuildIdentifier buildIdentifier;
    private final Set<PartialBasicGradleProject> projects = new LinkedHashSet<>();
    private final Set<DefaultGradleBuild> includedBuilds = new LinkedHashSet<>();
    private final Set<DefaultGradleBuild> allBuilds = new LinkedHashSet<>();

    @Override
    public String toString() {
        return buildIdentifier.toString();
    }

    public PartialBasicGradleProject getRootProject() {
        return rootProject;
    }

    public DefaultGradleBuild setRootProject(PartialBasicGradleProject rootProject) {
        this.rootProject = rootProject;
        this.buildIdentifier = new DefaultBuildIdentifier(rootProject.getRootDir());
        return this;
    }

    public Set<? extends PartialBasicGradleProject> getProjects() {
        return projects;
    }

    public void addProject(PartialBasicGradleProject project) {
        projects.add(project);
    }

    public Set<DefaultGradleBuild> getEditableBuilds() {
        return allBuilds;
    }

    public void addBuilds(Collection<DefaultGradleBuild> builds) {
        allBuilds.addAll(builds);
    }

    public Set<DefaultGradleBuild> getIncludedBuilds() {
        return includedBuilds;
    }

    public void addIncludedBuild(DefaultGradleBuild includedBuild) {
        includedBuilds.add(includedBuild);
    }

    public DefaultBuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public File getRootDir() {
        return getBuildIdentifier().getRootDir();
    }
}