package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.InvalidUserCodeException;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;


public class TransformBackedProvider<OUT, IN> extends AbstractMinimalProvider<OUT> {
    private final Transformer<? extends OUT, ? super IN> transformer;
    private final ProviderInternal<? extends IN> provider;

    public TransformBackedProvider(Transformer<? extends OUT, ? super IN> transformer, ProviderInternal<? extends IN> provider) {
        this.transformer = transformer;
        this.provider = provider;
    }

    @Nullable
    @Override
    public Class<OUT> getType() {
        // Could do a better job of inferring this
        return null;
    }

    public Transformer<? extends OUT, ? super IN> getTransformer() {
        return transformer;
    }

    @Override
    public ValueProducer getProducer() {
        return provider.getProducer();
    }

    @Override
    public ExecutionTimeValue<? extends OUT> calculateExecutionTimeValue() {
        ExecutionTimeValue<? extends IN> value = provider.calculateExecutionTimeValue();
        if (value.hasChangingContent()) {
            // Need the value contents in order to transform it to produce the value of this provider, so if the value or its contents are built by tasks, the value of this provider is also built by tasks
            return ExecutionTimeValue.changingValue(new TransformBackedProvider<OUT, IN>(transformer, value.toProvider()));
        } else {
            return ExecutionTimeValue.value(mapValue(value.toValue()));
        }
    }

    @Override
    protected Value<? extends OUT> calculateOwnValue(ValueConsumer consumer) {
        beforeRead();
        Value<? extends IN> value = provider.calculateValue(consumer);
        return mapValue(value);
    }

    @NotNull
    private Value<? extends OUT> mapValue(Value<? extends IN> value) {
        if (value.isMissing()) {
            return value.asType();
        }
        OUT result = transformer.transform(value.get());
        if (result == null) {
            return Value.missing();
        }
        return Value.of(result);
    }

    private void beforeRead() {
        provider.getProducer().visitContentProducerTasks(producer -> {
            if (!producer.getState().getExecuted()) {
                throw new InvalidUserCodeException(
                        String.format("Querying the mapped value of %s before %s has completed is not supported", provider, producer)
                );
            }
        });
    }

    @Override
    public String toString() {
        return "map(" + provider + ")";
    }
}