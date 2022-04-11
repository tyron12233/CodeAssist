package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.internal.execution.history.InputChangesInternal;

public interface InputChangesAwareTaskAction extends ImplementationAwareTaskAction, Describable {
    void setInputChanges(InputChangesInternal inputChanges);
    void clearInputChanges();
}