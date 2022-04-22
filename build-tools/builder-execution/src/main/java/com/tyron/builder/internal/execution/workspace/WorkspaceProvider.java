package com.tyron.builder.internal.execution.workspace;

import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface WorkspaceProvider {
    /**
     * Provides a workspace and execution history store for executing the transformation.
     */
    <T> T withWorkspace(String path, WorkspaceAction<T> action);

    @FunctionalInterface
    interface WorkspaceAction<T> {
        T executeInWorkspace(File workspace, @Nullable ExecutionHistoryStore history);
    }
}
