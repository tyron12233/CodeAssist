package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.history.ExecutionHistoryStore;

import java.io.File;
import java.util.Optional;

public interface WorkspaceContext extends IdentityContext {
    File getWorkspace();

    Optional<ExecutionHistoryStore> getHistory();
}