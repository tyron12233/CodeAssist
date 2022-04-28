package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;

import java.io.File;
import java.util.Optional;

public interface WorkspaceContext extends IdentityContext {
    File getWorkspace();

    Optional<ExecutionHistoryStore> getHistory();
}
