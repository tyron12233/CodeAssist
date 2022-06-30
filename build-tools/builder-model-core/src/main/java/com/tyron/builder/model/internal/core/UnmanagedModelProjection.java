package com.tyron.builder.model.internal.core;

import com.google.common.base.Optional;
import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import static com.tyron.builder.internal.reflect.JavaPropertyReflectionUtil.hasDefaultToString;
import static com.tyron.builder.model.internal.manage.schema.extract.PrimitiveTypes.defaultValueOf;
import static com.tyron.builder.model.internal.manage.schema.extract.PrimitiveTypes.isPrimitiveType;
import static com.tyron.builder.model.internal.manage.schema.extract.ScalarTypes.isScalarType;

@ThreadSafe
public class UnmanagedModelProjection<M> extends TypeCompatibilityModelProjectionSupport<M> {

    public static <M> ModelProjection of(ModelType<M> type) {
        return new UnmanagedModelProjection<M>(type);
    }

    public static <M> ModelProjection of(Class<M> type) {
        return of(ModelType.of(type));
    }

    public UnmanagedModelProjection(ModelType<M> type) {
        super(type);
    }

    @Override
    protected ModelView<M> toView(MutableModelNode modelNode, ModelRuleDescriptor ruleDescriptor, boolean writable) {
        M instance = Cast.uncheckedCast(modelNode.getPrivateData());
        return InstanceModelView.of(modelNode.getPath(), getType(), instance);
    }

    @Override
    public Optional<String> getValueDescription(MutableModelNode modelNode) {
        Object instance = this.asImmutable(ModelType.untyped(), modelNode, null).getInstance();
        if (instance == null) {
            if (isPrimitiveType(getType())) {
                if (getType().equals(ModelType.of(char.class))) {
                    return Optional.of("");
                } else {
                    return Optional.of(String.valueOf(defaultValueOf(getType())));
                }
            }
            if (isScalarType(getType())) {
                return Optional.of("null");
            }
            return Optional.absent();
        }
        if (hasDefaultToString(instance)) {
            return Optional.absent();
        }
        return Optional.of(toStringValueDescription(instance));
    }
}
