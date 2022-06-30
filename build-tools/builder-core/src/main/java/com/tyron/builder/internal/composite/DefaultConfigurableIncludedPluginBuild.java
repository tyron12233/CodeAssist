package com.tyron.builder.internal.composite;


import com.google.common.base.Preconditions;
import com.tyron.builder.api.tasks.TaskReference;
import com.tyron.builder.api.initialization.ConfigurableIncludedPluginBuild;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;

public class DefaultConfigurableIncludedPluginBuild implements ConfigurableIncludedPluginBuild {

    private final File projectDir;

    private String name;

    public DefaultConfigurableIncludedPluginBuild(File projectDir) {
        this.projectDir = projectDir;
        this.name = projectDir.getName();
    }

    @Override
    @Nonnull
    public String getName() {
        return name;
    }

    @Override
    public void setName(@Nonnull String name) {
        Preconditions.checkNotNull(name, "name must not be null");
        this.name = name;
    }

    @Override
    @Nonnull
    public File getProjectDir() {
        return projectDir;
    }

    @Override
    @Nonnull
    public TaskReference task(@Nullable String path) {
        throw new IllegalStateException("IncludedBuild.task() cannot be used while configuring the included build");
    }
}