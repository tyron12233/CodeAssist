package org.gradle.api.internal.tasks;

import org.gradle.api.Describable;
import org.gradle.internal.execution.history.changes.InputChangesInternal;

public interface InputChangesAwareTaskAction extends ImplementationAwareTaskAction, Describable {
    void setInputChanges(InputChangesInternal inputChanges);
    void clearInputChanges();
}