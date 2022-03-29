package com.tyron.builder.api.internal.execution.history;

import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.api.work.InputChanges;

public interface InputChangesInternal extends InputChanges {
    Iterable<InputFileDetails> getAllFileChanges();
}