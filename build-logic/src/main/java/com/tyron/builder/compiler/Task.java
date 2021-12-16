package com.tyron.builder.compiler;

import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.project.api.Module;

import java.io.IOException;

/**
 *
 */
public abstract class Task<T extends Module> {

    private final T mProject;
    private final ILogger mLogger;

    public Task(T project, ILogger logger) {
        mProject = project;
        mLogger = logger;
    }

    /**
     * @return the logger class that this task can use to write logs to
     */
    protected ILogger getLogger() {
        return mLogger;
    }

    /**
     * @return the Project that this task belongs to
     */
    protected T getModule() {
        return mProject;
    }

    /**
     * Called by {@link ApkBuilder} to display the name of the task to the logs
     */
    public abstract String getName();

    /**
     * Called before run() to give the subclass information about the project
     * @throws IOException if an exception occurred during a file operation
     */
    public abstract void prepare(BuildType type) throws IOException;

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
