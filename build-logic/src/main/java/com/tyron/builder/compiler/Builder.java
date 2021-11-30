package com.tyron.builder.compiler;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.Project;

public interface Builder<T extends Project> {

    T getProject();

    void build(BuildType type) throws CompilationFailedException;

    ILogger getLogger();
}
