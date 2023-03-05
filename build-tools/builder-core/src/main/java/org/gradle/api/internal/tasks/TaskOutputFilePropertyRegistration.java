package org.gradle.api.internal.tasks;


import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.tasks.TaskOutputFilePropertyBuilder;

public interface TaskOutputFilePropertyRegistration extends TaskPropertyRegistration, TaskOutputFilePropertyBuilder {
    OutputFilePropertyType getPropertyType();
}