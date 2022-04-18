package com.tyron.builder.internal;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.tyron.builder.TaskExecutionRequest;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Serializable;
import java.util.List;

public class DefaultTaskExecutionRequest implements TaskExecutionRequest, Serializable {
    private final List<String> args;
    private final String projectPath;
    private final File rootDir;

    public DefaultTaskExecutionRequest(Iterable<String> args) {
        this(args, null, null);
    }

    public DefaultTaskExecutionRequest(Iterable<String> args, @Nullable String projectPath) {
        this(args, projectPath, null);
    }

    public DefaultTaskExecutionRequest(Iterable<String> args, @Nullable String projectPath, @Nullable File rootDir) {
        this.args = Lists.newArrayList(args);
        this.projectPath = projectPath;
        this.rootDir = rootDir;
    }

    @Override
    public List<String> getArgs() {
        return args;
    }

    @Override
    public String getProjectPath() {
        return projectPath;
    }

    @Override
    public File getRootDir() {
        return rootDir;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultTaskExecutionRequest that = (DefaultTaskExecutionRequest) o;
        if (!Objects.equal(projectPath, that.projectPath)) {
            return false;
        }
        if (!Objects.equal(args, that.args)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = getArgs().hashCode();
        result = 31 * result + (projectPath != null ? projectPath.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DefaultTaskExecutionRequest{"
               + "args=" + args
               + ",projectPath='" + projectPath + '\''
               + ",rootDir='" + rootDir + '\''
               + '}';
    }
}
