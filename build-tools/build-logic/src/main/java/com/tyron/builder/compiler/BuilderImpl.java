package com.tyron.builder.compiler;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class BuilderImpl<T extends Module> implements Builder<T> {

    private final Handler mMainHandler;
    private final Project mProject;
    private final T mModule;
    private final ILogger mLogger;
    private final List<Task<? super T>> mTasksRan;
    private TaskListener mTaskListener;

    public BuilderImpl(Project project, T module, ILogger logger) {
        mProject = project;
        mModule = module;
        mLogger = logger;
        mMainHandler = new Handler(Looper.getMainLooper());
        mTasksRan = new ArrayList<>();
    }

    @NonNull
    @Override
    public Project getProject() {
        return mProject;
    }

    @Override
    public void setTaskListener(TaskListener taskListener) {
        mTaskListener = taskListener;
    }

    @Override
    public T getModule() {
        return mModule;
    }

    protected void updateProgress(String name, String message, int progress) {
        if (mTaskListener != null) {
            mTaskListener.onTaskStarted(name, message, progress);
        }
    }

    @Override
    public final void build(BuildType type) throws CompilationFailedException, IOException {
        mTasksRan.clear();
        List<Task<? super T>> tasks = getTasks(type);
        for (int i = 0, tasksSize = tasks.size(); i < tasksSize; i++) {
            Task<? super T> task = tasks.get(i);
            final float current = i;
            getLogger().info("Running " + task.getName());
            try {
                mMainHandler.post(() -> updateProgress(task.getName(), "Task started",
                        (int) ((current / (float) tasks.size()) * 100f)));
                task.prepare(type);
                task.run();
            } catch (Throwable e) {
                if (e instanceof OutOfMemoryError) {
                    tasks.clear();
                    mTasksRan.clear();
                    throw new CompilationFailedException("Builder ran out of memory", e);
                }
                task.clean();
                mTasksRan.forEach(Task::clean);
                throw e;
            }
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
