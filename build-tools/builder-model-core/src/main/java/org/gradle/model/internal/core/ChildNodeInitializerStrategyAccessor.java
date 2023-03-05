package org.gradle.model.internal.core;

public interface ChildNodeInitializerStrategyAccessor<T> {
    ChildNodeInitializerStrategy<T> getStrategy(MutableModelNode node);
}
