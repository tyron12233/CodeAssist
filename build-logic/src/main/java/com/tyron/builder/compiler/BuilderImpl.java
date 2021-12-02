package com.tyron.builder.compiler;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.Project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class BuilderImpl<T extends Project> implements Builder<T> {

    private final T mProject;
    private final ILogger mLogger;
    private final List<Task<? super T>> mTasksRan;

    public BuilderImpl(T project, ILogger logger) {
        mProject = project;
        mLogger = logger;
        mTasksRan = new ArrayList<>();
    }

    @Override
    public T getProject() {
        return mProject;
    }

    @Override
    public final void build(BuildType type) throws CompilationFailedException, IOException {
        mTasksRan.clear();
        for (Task<? super T> task : getTasks(type)) {
            getLogger().info("Running " + task.getName());
            task.prepare(type);
            task.run();
            mTasksRan.add(task);
        }
        mTasksRan.forEach(Task::clean);
    }

    public abstract List<Task<? super T>> getTasks(BuildType type);

    /**
     * Used in tests to check the values of tasks that ran
     */
    @VisibleForTesting
    public List<Task<? super T>> getTasksRan() {
        return mTasksRan;
    }

    @Override
    public ILogger getLogger() {
        return mLogger;
    }
}
