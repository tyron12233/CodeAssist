package com.tyron.builder.model.internal.core;

public interface ChildNodeInitializerStrategyAccessor<T> {
    ChildNodeInitializerStrategy<T> getStrategy(MutableModelNode node);
}
