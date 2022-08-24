package org.gradle.execution;

import org.gradle.api.Task;
import org.gradle.api.specs.Spec;

import javax.annotation.Nullable;
import java.io.File;

public abstract class TaskSelector {
    public abstract Spec<Task> getFilter(String path);

    public abstract TaskSelection getSelection(String path);

    public abstract TaskSelection getSelection(@Nullable String projectPath, @Nullable File root, String path);
}