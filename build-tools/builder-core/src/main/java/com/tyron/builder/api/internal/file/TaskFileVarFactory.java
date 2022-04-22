package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;

public interface TaskFileVarFactory {
    /**
     * Creates a {@link ConfigurableFileCollection} that can be used as a task input.
     *
     * <p>The implementation may apply caching to the result, so that the matching files are calculated during file snapshotting and the result cached in memory for when it is queried again, either during task action execution or in order to calculate some other task input value.
     *
     * <p>Use this collection only for those files that are not expected to change during task execution, such as task inputs.
     */
    ConfigurableFileCollection newInputFileCollection(Task consumer);

    /**
     * Creates a {@link FileCollection} that represents some task input that is calculated from one or more other file collections.
     *
     * <p>The implementation applies caching to the result, so that the matching files are calculated during file snapshotting and the result cached in memory for when it is queried again, either during task action execution or in order to calculate some other task input value.
     *
     * <p>Use this collection only for those files that are not expected to change during task execution, such as task inputs.
     */
    FileCollection newCalculatedInputFileCollection(Task consumer, MinimalFileSet calculatedFiles, FileCollection... inputs);
}

