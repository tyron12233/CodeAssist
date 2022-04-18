package com.tyron.builder.api.internal.tasks;


import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.tasks.TaskDestroyables;

/**
 * Note: this is currently not visible on {@link org.gradle.api.internal.TaskInternal} to avoid it leaking onto {@link org.gradle.api.internal.AbstractTask} and so on to the public API.
 */
public interface TaskDestroyablesInternal extends TaskDestroyables {

    void visitRegisteredProperties(PropertyVisitor visitor);

    FileCollection getRegisteredFiles();
}