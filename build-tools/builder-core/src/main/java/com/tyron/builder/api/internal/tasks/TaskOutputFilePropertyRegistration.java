package com.tyron.builder.api.internal.tasks;


import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.tasks.TaskOutputFilePropertyBuilder;

public interface TaskOutputFilePropertyRegistration extends TaskPropertyRegistration, TaskOutputFilePropertyBuilder {
    OutputFilePropertyType getPropertyType();
}