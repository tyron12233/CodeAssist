package org.gradle.plugins.ide.internal.tooling.idea;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

public class DefaultIdeaContentRoot implements Serializable {

    File rootDirectory;
    Set<DefaultIdeaSourceDirectory> sourceDirectories = new LinkedHashSet<DefaultIdeaSourceDirectory>();
    Set<DefaultIdeaSourceDirectory> testDirectories = new LinkedHashSet<DefaultIdeaSourceDirectory>();
    Set<DefaultIdeaSourceDirectory> resourceDirectories = new LinkedHashSet<DefaultIdeaSourceDirectory>();
    Set<DefaultIdeaSourceDirectory> testResourceDirectories = new LinkedHashSet<DefaultIdeaSourceDirectory>();
    Set<File> excludeDirectories = new LinkedHashSet<File>();

    public File getRootDirectory() {
        return rootDirectory;
    }

    public DefaultIdeaContentRoot setRootDirectory(File rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    public Set<DefaultIdeaSourceDirectory> getSourceDirectories() {
        return sourceDirectories;
    }

    public DefaultIdeaContentRoot setSourceDirectories(Set<DefaultIdeaSourceDirectory> sourceDirectories) {
        this.sourceDirectories = sourceDirectories;
        return this;
    }

    public Set<DefaultIdeaSourceDirectory> getTestDirectories() {
        return testDirectories;
    }

    public DefaultIdeaContentRoot setTestDirectories(Set<DefaultIdeaSourceDirectory> testDirectories) {
        this.testDirectories = testDirectories;
        return this;
    }

    public Set<DefaultIdeaSourceDirectory> getResourceDirectories() {
        return resourceDirectories;
    }

    public DefaultIdeaContentRoot setResourceDirectories(Set<DefaultIdeaSourceDirectory> resourceDirectories) {
        this.resourceDirectories = resourceDirectories;
        return this;
    }

    public Set<DefaultIdeaSourceDirectory> getTestResourceDirectories() {
        return testResourceDirectories;
    }

    public DefaultIdeaContentRoot setTestResourceDirectories(Set<DefaultIdeaSourceDirectory> testResourceDirectories) {
        this.testResourceDirectories = testResourceDirectories;
        return this;
    }


    public Set<File> getExcludeDirectories() {
        return excludeDirectories;
    }

    public DefaultIdeaContentRoot setExcludeDirectories(Set<File> excludeDirectories) {
        this.excludeDirectories = excludeDirectories;
        return this;
    }

    @Override
    public String toString() {
        return "IdeaContentRoot{"
                + "rootDirectory=" + rootDirectory
                + ", sourceDirectories count=" + sourceDirectories.size()
                + ", testDirectories count=" + testDirectories.size()
                + ", resourceDirectories count=" + resourceDirectories.size()
                + ", testResourceDirectories count=" + testResourceDirectories.size()
                + ", excludeDirectories count=" + excludeDirectories.size()
                + '}';
    }
}