package com.tyron.builder.api.internal.tasks.compile;

import com.google.common.collect.ImmutableList;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Serializable;
import java.util.List;

public class DefaultJvmLanguageCompileSpec implements JvmLanguageCompileSpec, Serializable {
    private File workingDir;
    private File tempDir;
    private List<File> classpath;
    private File destinationDir;
    private Iterable<File> sourceFiles;
    private Integer release;
    private String sourceCompatibility;
    private String targetCompatibility;
    private List<File> sourceRoots;

    @Override
    public File getWorkingDir() {
        return workingDir;
    }

    @Override
    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public File getDestinationDir() {
        return destinationDir;
    }

    @Override
    public void setDestinationDir(File destinationDir) {
        this.destinationDir = destinationDir;
    }

    @Override
    public File getTempDir() {
        return tempDir;
    }

    @Override
    public void setTempDir(File tempDir) {
        this.tempDir = tempDir;
    }

    @Override
    public Iterable<File> getSourceFiles() {
        return sourceFiles;
    }

    @Override
    public void setSourceFiles(Iterable<File> sourceFiles) {
        this.sourceFiles = sourceFiles;
    }

    @Override
    public List<File> getCompileClasspath() {
        if (classpath == null) {
            classpath = ImmutableList.of();
        }
        return classpath;
    }

    @Override
    public void setCompileClasspath(List<File> classpath) {
        this.classpath = classpath;
    }

    @Override
    @Nullable
    public Integer getRelease() {
        return release;
    }

    @Override
    public void setRelease(@Nullable Integer release) {
        this.release = release;
    }

    @Override
    @Nullable
    public String getSourceCompatibility() {
        return sourceCompatibility;
    }

    @Override
    public void setSourceCompatibility(@Nullable String sourceCompatibility) {
        this.sourceCompatibility = sourceCompatibility;
    }

    @Override
    @Nullable
    public String getTargetCompatibility() {
        return targetCompatibility;
    }

    @Override
    public void setTargetCompatibility(@Nullable String targetCompatibility) {
        this.targetCompatibility = targetCompatibility;
    }

    @Override
    public List<File> getSourceRoots() {
        return sourceRoots;
    }

    @Override
    public void setSourcesRoots(List<File> sourceRoots) {
        this.sourceRoots = sourceRoots;
    }
}
