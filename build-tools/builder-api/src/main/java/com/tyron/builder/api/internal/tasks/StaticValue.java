package com.tyron.builder.api.internal.tasks;


import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.provider.HasConfigurableValueInternal;
import com.tyron.builder.api.internal.provider.PropertyInternal;
import com.tyron.builder.api.internal.state.ModelObject;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.providers.HasConfigurableValue;
import com.tyron.builder.api.tasks.Buildable;

import org.jetbrains.annotations.Nullable;

/**
 * A {@link PropertyValue} backed by a fixed value.
 */
public class StaticValue implements PropertyValue {
    private final Object value;

    public StaticValue(@Nullable Object value) {
        this.value = value;
    }

    public static PropertyValue of(@Nullable Object value) {
        return new StaticValue(value);
    }

    @Override
    public TaskDependencyContainer getTaskDependencies() {
        if (value instanceof TaskDependencyContainer) {
            return (TaskDependencyContainer) value;
        }
        if (value instanceof Buildable) {
            return context -> context.add(value);
        }
        return TaskDependencyContainer.EMPTY;
    }

    public void attachProducer(Task producer) {
        if (value instanceof PropertyInternal) {
            ((PropertyInternal<?>) value).attachProducer((ModelObject) producer);
        }
    }

    @Override
    public void maybeFinalizeValue() {
        if (value instanceof HasConfigurableValue) {
            ((HasConfigurableValueInternal) value).implicitFinalizeValue();
        }
    }

    @Nullable
    @Override
    public Object call() {
        return value;
    }

    @Nullable
    @Override
    public Object getUnprocessedValue() {
        return value;
    }
}