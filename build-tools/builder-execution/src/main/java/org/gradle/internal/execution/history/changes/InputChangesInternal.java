package org.gradle.internal.execution.history.changes;

import org.gradle.api.tasks.incremental.InputFileDetails;
import org.gradle.work.InputChanges;

public interface InputChangesInternal extends InputChanges {
    Iterable<InputFileDetails> getAllFileChanges();
}