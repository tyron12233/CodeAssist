package com.tyron.builder.api.internal.provider;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

import org.jetbrains.annotations.Nullable;

class OrElseValueProducer implements ValueSupplier.ValueProducer {

    private final ProviderInternal<?> left;
    @Nullable
    private final ProviderInternal<?> right;
    private final ValueSupplier.ValueProducer leftProducer;
    private final ValueSupplier.ValueProducer rightProducer;

    public OrElseValueProducer(ProviderInternal<?> left, @Nullable ProviderInternal<?> right, ValueSupplier.ValueProducer rightProducer) {
        this.left = left;
        this.right = right;
        this.leftProducer = left.getProducer();
        this.rightProducer = rightProducer;
    }

    @Override
    public boolean isKnown() {
        return leftProducer.isKnown()
               || rightProducer.isKnown();
    }

    @Override
    public boolean isProducesDifferentValueOverTime() {
        return leftProducer.isProducesDifferentValueOverTime()
               || rightProducer.isProducesDifferentValueOverTime();
    }

    @Override
    public void visitProducerTasks(Action<? super Task> visitor) {
        if (!isMissing(left)) {
            if (leftProducer.isKnown()) {
                leftProducer.visitProducerTasks(visitor);
            }
            return;
        }
        if (right != null && rightProducer.isKnown() && !isMissing(right)) {
            rightProducer.visitProducerTasks(visitor);
        }
    }

    private boolean isMissing(ProviderInternal<?> provider) {
        return provider.calculateExecutionTimeValue().isMissing();
    }
}