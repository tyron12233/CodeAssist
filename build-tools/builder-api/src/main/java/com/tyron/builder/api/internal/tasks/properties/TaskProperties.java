package com.tyron.builder.api.internal.tasks.properties;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

public interface TaskProperties {
    /**
     * The lifecycle aware values.
     */
    Iterable<? extends LifecycleAwareValue> getLifecycleAwareValues();

    /**
     * Input properties.
     */
    ImmutableSortedSet<InputPropertySpec> getInputProperties();

    /**
     * Input file properties.
     *
     * It is guaranteed that all the {@link InputFilePropertySpec}s have a name and that the names are unique.
     */
    ImmutableSortedSet<InputFilePropertySpec> getInputFileProperties();

    /**
     * Output file properties.
     *
     * It is guaranteed that all the {@link OutputFilePropertySpec}s have a name and that the names are unique.
     */
    ImmutableSortedSet<OutputFilePropertySpec> getOutputFileProperties();

    /**
     * Whether output properties have been declared.
     */
    boolean hasDeclaredOutputs();

    /**
     * The files that represent the local state.
     */
    FileCollection getLocalStateFiles();

    /**
     * The files that are destroyed.
     */
    FileCollection getDestroyableFiles();

    /**
     * Validate the task type.
     */
    void validateType(TypeValidationContext validationContext);

    /**
     * Validations for the properties.
     */
    void validate(TaskValidationContext validationContext);
}