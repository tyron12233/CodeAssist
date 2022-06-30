package com.tyron.builder.model.internal.core;


import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.tyron.builder.model.internal.type.ModelType;

public class ModelNodes {
    public static Predicate<MutableModelNode> all() {
        return Predicates.alwaysTrue();
    }

    public static Predicate<MutableModelNode> withType(Class<?> type) {
        return withType(ModelType.of(type));
    }

    public static Predicate<MutableModelNode> withType(final ModelType<?> type) {
        return withType(type, Predicates.<MutableModelNode>alwaysTrue());
    }

    public static Predicate<MutableModelNode> withType(Class<?> type, Predicate<? super MutableModelNode> predicate) {
        return withType(ModelType.of(type), predicate);
    }

    public static Predicate<MutableModelNode> withType(final ModelType<?> type, final Predicate<? super MutableModelNode> predicate) {
        return new Predicate<MutableModelNode>() {
            @Override
            public boolean apply(MutableModelNode node) {
                node.ensureAtLeast(ModelNode.State.Discovered);
                return node.canBeViewedAs(type) && predicate.apply(node);
            }
        };
    }
}