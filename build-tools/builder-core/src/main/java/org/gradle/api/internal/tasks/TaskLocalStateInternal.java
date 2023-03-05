package org.gradle.api.internal.tasks;


import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.tasks.TaskLocalState;

// Note: this is currently not visible on {@link org.gradle.api.internal.TaskInternal} to avoid it leaking onto AbstractTask and so on to the public API.
public interface TaskLocalStateInternal extends TaskLocalState {

    void visitRegisteredProperties(PropertyVisitor visitor);

    FileCollection getRegisteredFiles();
}