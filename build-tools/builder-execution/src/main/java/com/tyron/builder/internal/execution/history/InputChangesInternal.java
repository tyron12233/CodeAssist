package com.tyron.builder.internal.execution.history;

import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.work.InputChanges;

public interface InputChangesInternal extends InputChanges {
    Iterable<InputFileDetails> getAllFileChanges();
}