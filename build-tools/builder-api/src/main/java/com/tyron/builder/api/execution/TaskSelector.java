package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Predicate;

public abstract class TaskSelector {
    public abstract Predicate<Task> getFilter(String path);

    public abstract TaskSelection getSelection(String path);

    public abstract TaskSelection getSelection(@Nullable String projectPath, @Nullable File root, String path);
}
