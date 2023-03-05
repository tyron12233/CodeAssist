package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.plugins.ide.internal.tooling.model.DefaultGradleProject;
import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;
import org.gradle.tooling.model.idea.IdeaCompilerOutput;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class DefaultIdeaModule implements Serializable, GradleProjectIdentity {
    private String name;
    private List<DefaultIdeaContentRoot> contentRoots = new LinkedList<DefaultIdeaContentRoot>();
    private DefaultIdeaProject parent;

    private List<DefaultIdeaDependency> dependencies = new LinkedList<DefaultIdeaDependency>();
    private DefaultGradleProject gradleProject;

    private IdeaCompilerOutput compilerOutput;

    private DefaultIdeaJavaLanguageSettings javaLanguageSettings;
    private String jdkName;

    public String getName() {
        return name;
    }

    public DefaultIdeaModule setName(String name) {
        this.name = name;
        return this;
    }

    public Collection<DefaultIdeaContentRoot> getContentRoots() {
        return contentRoots;
    }

    public DefaultIdeaModule setContentRoots(List<DefaultIdeaContentRoot> contentRoots) {
        this.contentRoots = contentRoots;
        return this;
    }

    public DefaultIdeaProject getParent() {
        return parent;
    }

    public DefaultIdeaProject getProject() {
        return parent;
    }

    public DefaultIdeaModule setParent(DefaultIdeaProject parent) {
        this.parent = parent;
        return this;
    }

    public Collection<DefaultIdeaDependency> getDependencies() {
        return dependencies;
    }

    public DefaultIdeaModule setDependencies(List<DefaultIdeaDependency> dependencies) {
        this.dependencies = dependencies;
        return this;
    }

    public Collection<Object> getChildren() {
        return Collections.emptySet();
    }

    public String getDescription() {
        return null;
    }

    public DefaultGradleProject getGradleProject() {
        return gradleProject;
    }

    public DefaultIdeaModule setGradleProject(DefaultGradleProject gradleProject) {
        this.gradleProject = gradleProject;
        return this;
    }

    public IdeaCompilerOutput getCompilerOutput() {
        return compilerOutput;
    }

    public DefaultIdeaModule setCompilerOutput(IdeaCompilerOutput compilerOutput) {
        this.compilerOutput = compilerOutput;
        return this;
    }

    public DefaultIdeaJavaLanguageSettings getJavaLanguageSettings() {
        return javaLanguageSettings;
    }

    public DefaultIdeaModule setJavaLanguageSettings(DefaultIdeaJavaLanguageSettings javaLanguageSettings) {
        this.javaLanguageSettings = javaLanguageSettings;
        return this;
    }

    public String getJdkName() {
        return jdkName;
    }

    public DefaultIdeaModule setJdkName(String jdkName) {
        this.jdkName = jdkName;
        return this;
    }

    public DefaultProjectIdentifier getProjectIdentifier() {
        return gradleProject.getProjectIdentifier();
    }

    @Override
    public String getProjectPath() {
        return getProjectIdentifier().getProjectPath();
    }

    @Override
    public File getRootDir() {
        return getProjectIdentifier().getBuildIdentifier().getRootDir();
    }

    @Override
    public String toString() {
        return "IdeaModule{"
                + "name='" + name + '\''
                + ", gradleProject='" + gradleProject + '\''
                + ", contentRoots=" + contentRoots
                + ", compilerOutput=" + compilerOutput
                + ", dependencies count=" + dependencies.size()
                + '}';
    }
}