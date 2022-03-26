package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.initialization.ProjectDescriptor;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;

public class DefaultProjectDescriptor implements ProjectDescriptor {
    private String name;
    private File projectDir;
    private String buildFileName;
    private File buildFile;
    private ProjectDescriptor parent;
    private String path;
    private Set<ProjectDescriptor> children;

    public DefaultProjectDescriptor(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public File getProjectDir() {
        return this.projectDir;
    }

    @Override
    public void setProjectDir(File dir) {
        this.projectDir = dir;
    }

    @Override
    public String getBuildFileName() {
        return buildFileName;
    }

    @Override
    public void setBuildFileName(String name) {
        this.buildFileName = name;
    }

    @Override
    public File getBuildFile() {
        return this.buildFile;
    }

    @Nullable
    @Override
    public ProjectDescriptor getParent() {
        return parent;
    }

    @Override
    public Set<ProjectDescriptor> getChildren() {
        return children;
    }

    @Override
    public String getPath() {
        return path;
    }
}
