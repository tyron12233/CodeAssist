package com.tyron.builder.api.tasks;

/**
 * Describes an output property of a task that contains zero or more files.
 *
 * @since 3.0
 */
public interface TaskOutputFilePropertyBuilder extends TaskFilePropertyBuilder {
    /**
     * {@inheritDoc}
     */
    @Override
    TaskOutputFilePropertyBuilder withPropertyName(String propertyName);

    /**
     * Marks a task property as optional. This means that a value does not have to be specified for the property, but any
     * value specified must meet the validation constraints for the property.
     */
    TaskOutputFilePropertyBuilder optional();

    /**
     * Sets whether the task property is optional. If the task property is optional, it means that a value does not have to be
     * specified for the property, but any value specified must meet the validation constraints for the property.
     */
    TaskOutputFilePropertyBuilder optional(boolean optional);
}