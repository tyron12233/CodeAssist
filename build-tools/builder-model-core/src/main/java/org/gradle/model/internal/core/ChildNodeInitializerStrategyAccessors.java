package org.gradle.model.internal.core;

import org.gradle.internal.Cast;
import org.gradle.model.internal.type.ModelType;

public class ChildNodeInitializerStrategyAccessors {

    private static final ModelType<ChildNodeInitializerStrategy<?>> CHILD_NODE_INITIALIZER_STRATEGY_MODEL_TYPE =
        Cast.uncheckedCast(ModelType.of(ChildNodeInitializerStrategy.class));

    public static <T> ChildNodeInitializerStrategyAccessor<T> fromPrivateData() {
        return node -> Cast.uncheckedCast(node.getPrivateData(CHILD_NODE_INITIALIZER_STRATEGY_MODEL_TYPE));
    }

    public static <T> ChildNodeInitializerStrategyAccessor<T> of(final ChildNodeInitializerStrategy<T> strategy) {
        return node -> strategy;
    }
}
