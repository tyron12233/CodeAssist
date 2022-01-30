package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

/**
 * <p>A {@code TaskInputs} represents the inputs for a task.</p>
 *
 * <p>You can obtain a {@code TaskInputs} instance using {@link Task#getInputs()}.</p>
 */
public interface TaskInputs {

    /**
     * Returns true if this task has declared the inputs that it consumes.
     *
     * @return true if this task has declared any inputs.
     */
    boolean getHasInputs();

    /**
     * Returns true if this task has declared that it accepts source files.
     *
     * @return true if this task has source files, false if not.
     */
    boolean getHasSourceFiles();
}
