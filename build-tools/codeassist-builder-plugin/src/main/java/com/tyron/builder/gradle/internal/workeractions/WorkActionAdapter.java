package com.tyron.builder.gradle.internal.workeractions;

import org.gradle.workers.WorkAction;

import java.io.Serializable;

public interface WorkActionAdapter<WorkItemParametersT extends DecoratedWorkParameters> extends WorkAction<WorkItemParametersT>, Serializable {

    @Override
    default void execute() {
        doExecute();
    }

    void doExecute();
}
