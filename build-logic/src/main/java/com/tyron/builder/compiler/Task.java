package com.tyron.builder.compiler;

import com.tyron.builder.model.Project;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;

import java.io.IOException;

/**
 *
 */
public abstract class Task {

    /**
     * Called by {@link ApkBuilder} to display the name of the task to the logs
     */
    public abstract String getName();

    /**
     * Called before run() to give the subclass information about the project
     * @throws IOException if an exception occurred during a file operation
     */
    public abstract void prepare(Project project, ILogger logger, BuildType type) throws IOException;

    /**
     * Called by the {@link ApkBuilder} to perform the task needed to do by this subclass
     * @throws IOException if an exception occurred in a File operation
     * @throws CompilationFailedException if an exception occurred while the task is running
     */
    public abstract void run() throws IOException, CompilationFailedException;

    /**
     * Called after the compilation has finished successfully on every tasks
     */
    protected void clean() {

    }
}
