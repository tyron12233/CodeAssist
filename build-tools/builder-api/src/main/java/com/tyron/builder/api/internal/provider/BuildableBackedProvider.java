package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.tasks.Buildable;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class BuildableBackedProvider<T> extends AbstractProviderWithValue<T> {

    private final Buildable buildable;
    private final Class<T> valueType;
    private final Factory<T> valueFactory;

    public BuildableBackedProvider(Buildable buildable, Class<T> valueType, Factory<T> valueFactory) {
        this.buildable = buildable;
        this.valueType = valueType;
        this.valueFactory = valueFactory;
    }

    @Nullable
    @Override
    public Class<T> getType() {
        return valueType;
    }

    @Override
    public ValueProducer getProducer() {
        return new ValueProducer() {
            @Override
            public boolean isProducesDifferentValueOverTime() {
                return false;
            }

            @Override
            public void visitProducerTasks(Action<? super Task> visitor) {
                for (Task dependency : buildableDependencies()) {
                    visitor.execute(dependency);
                }
            }
        };
    }

    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        if (contentsAreBuiltByTask()) {
            return ExecutionTimeValue.changingValue(this);
        }
        return ExecutionTimeValue.fixedValue(get());
    }

    private boolean contentsAreBuiltByTask() {
        return !buildableDependencies().isEmpty();
    }

    private Set<? extends Task> buildableDependencies() {
        return buildable.getBuildDependencies().getDependencies(null);
    }

    @Override
    protected Value<? extends T> calculateOwnValue(ValueConsumer consumer) {
        return Value.ofNullable(valueFactory.create());
    }
}