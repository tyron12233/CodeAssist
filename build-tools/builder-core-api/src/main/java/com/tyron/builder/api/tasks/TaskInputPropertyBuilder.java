package com.tyron.builder.api.tasks;

/**
 * Describes an input property of a task.
 *
 * @since 4.3
 */
public interface TaskInputPropertyBuilder extends TaskPropertyBuilder {
    /**
     * Sets whether the task property is optional. If the task property is optional, it means that a value does not have to be
     * specified for the property, but any value specified must meet the validation constraints for the property.
     */
    TaskInputPropertyBuilder optional(boolean optional);
}