package com.tyron.builder.compiler;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;

import java.io.IOException;
import java.util.List;

public interface Builder<T extends Module> {

    interface TaskListener {
        @MainThread
        void onTaskStarted(String name, String message, int progress);
    }

    void setTaskListener(TaskListener taskListener);

    @NonNull
    Project getProject();

    T getModule();

    void build(BuildType type) throws CompilationFailedException, IOException;

    ILogger getLogger();

    List<Task<? super T>> getTasks(BuildType type);
}
