package com.tyron.builder.model.internal.core;

import static com.tyron.builder.model.internal.manage.schema.extract.PrimitiveTypes.isPrimitiveType;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import java.util.Collections;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public abstract class TypeCompatibilityModelProjectionSupport<M> implements ModelProjection {

    private final ModelType<M> type;

    public TypeCompatibilityModelProjectionSupport(ModelType<M> type) {
        this.type = type;
    }

    protected ModelType<M> getType() {
        return type;
    }

    @Override
    public <T> boolean canBeViewedAs(ModelType<T> targetType) {
        return canBeAssignedTo(targetType);
    }

    private <T> boolean canBeAssignedTo(ModelType<T> targetType) {
        return targetType.isAssignableFrom(type) ||
               (targetType == ModelType.UNTYPED && isPrimitiveType(type));
    }

    @Override
    public <T> ModelView<? extends T> asMutable(ModelType<T> type,
                                                MutableModelNode modelNode,
                                                ModelRuleDescriptor ruleDescriptor) {
        if (canBeViewedAs(type)) {
            return Cast.uncheckedCast(toView(modelNode, ruleDescriptor, true));
        } else {
            return null;
        }
    }

    @Override
    public <T> ModelView<? extends T> asImmutable(ModelType<T> type,
                                                  MutableModelNode modelNode,
                                                  ModelRuleDescriptor ruleDescriptor) {
        if (canBeViewedAs(type)) {
            return Cast.uncheckedCast(toView(modelNode, ruleDescriptor, false));
        } else {
            return null;
        }
    }

    protected abstract ModelView<M> toView(MutableModelNode modelNode,
                                           ModelRuleDescriptor ruleDescriptor,
                                           boolean writable);

    @Override
    public Iterable<String> getTypeDescriptions(MutableModelNode node) {
        return Collections.singleton(description(type));
    }

    protected String toStringValueDescription(Object instance) {
        String valueDescription = instance.toString();
        if (valueDescription != null) {
            return valueDescription;
        }
        return new StringBuilder(type.toString()).append("#toString() returned null").toString();
    }

    public static String description(ModelType<?> type) {
        if (type.getRawClass().getSuperclass() == null &&
            type.getRawClass().getInterfaces().length == 0) {
            return type.toString();
        }
        return type.toString() + " (or assignment compatible type thereof)";
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + type + "]";
    }
}
