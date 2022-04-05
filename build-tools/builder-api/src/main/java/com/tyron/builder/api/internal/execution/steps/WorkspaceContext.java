package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.history.ExecutionHistoryStore;

import java.io.File;
import java.util.Optional;

public interface WorkspaceContext extends IdentityContext {
    File getWorkspace();

    Optional<ExecutionHistoryStore> getHistory();
}
