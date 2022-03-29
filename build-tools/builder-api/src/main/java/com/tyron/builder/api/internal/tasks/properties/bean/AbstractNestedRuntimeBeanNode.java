package com.tyron.builder.api.internal.tasks.properties.bean;

import com.google.common.base.Suppliers;
import com.tyron.builder.api.internal.provider.HasConfigurableValueInternal;
import com.tyron.builder.api.internal.reflect.PropertyMetadata;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.TypeMetadata;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import com.tyron.builder.api.providers.HasConfigurableValue;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.Buildable;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Queue;
import java.util.function.Supplier;

public abstract class AbstractNestedRuntimeBeanNode extends RuntimeBeanNode<Object> {
    protected AbstractNestedRuntimeBeanNode(@Nullable RuntimeBeanNode<?> parentNode, @Nullable String propertyName, Object bean, TypeMetadata typeMetadata) {
        super(parentNode, propertyName, bean, typeMetadata);
    }

    protected void visitProperties(PropertyVisitor visitor, final Queue<RuntimeBeanNode<?>> queue, final RuntimeBeanNodeFactory nodeFactory, TypeValidationContext validationContext) {
        TypeMetadata typeMetadata = getTypeMetadata();
        typeMetadata.visitValidationFailures(getPropertyName(), validationContext);
        for (PropertyMetadata propertyMetadata : typeMetadata.getPropertiesMetadata()) {
            PropertyAnnotationHandler annotationHandler = typeMetadata.getAnnotationHandlerFor(propertyMetadata);
//            if (annotationHandler.shouldVisit(visitor)) {
//                String propertyName = getQualifiedPropertyName(propertyMetadata.getPropertyName());
//                PropertyValue value = new BeanPropertyValue(getBean(), propertyMetadata.getGetterMethod());
//                annotationHandler.visitPropertyValue(propertyName, value, propertyMetadata, visitor, new BeanPropertyContext() {
//                    @Override
//                    public void addNested(String propertyName, Object bean) {
//                        queue.add(nodeFactory.create(AbstractNestedRuntimeBeanNode.this, propertyName, bean));
//                    }
//                });
//            }
        }
    }

    private static class BeanPropertyValue implements PropertyValue {
        private final Method method;
        private final Object bean;
        private final Supplier<Object> valueSupplier = Suppliers.memoize(() -> {
//            return DeprecationLogger.whileDisabled((Factory<Object>) () -> {
//                    try {
//                        return method.invoke(bean);
//                    } catch (InvocationTargetException e) {
//                        throw UncheckedException.throwAsUncheckedException(e.getCause());
//                    } catch (Exception e) {
//                        throw new BuildException(String.format("Could not call %s.%s() on %s", method.getDeclaringClass().getSimpleName(), method.getName(), bean), e);
//                    }
//                });
            return null;
        });

        public BeanPropertyValue(Object bean, Method method) {
            this.bean = bean;
            this.method = method;
            method.setAccessible(true);
        }

        @Override
        public TaskDependencyContainer getTaskDependencies() {
            if (isProvider()) {
                return (TaskDependencyContainer) valueSupplier.get();
            }
            if (isBuildable()) {
                return context -> {
                    Object dependency = valueSupplier.get();
                    if (dependency != null) {
                        context.add(dependency);
                    }
                };
            }
            return TaskDependencyContainer.EMPTY;
        }

        @Override
        public void maybeFinalizeValue() {
            if (isConfigurable()) {
                Object value = valueSupplier.get();
                ((HasConfigurableValueInternal) value).implicitFinalizeValue();
            }
        }

        private boolean isProvider() {
            return Provider.class.isAssignableFrom(method.getReturnType());
        }

        private boolean isConfigurable() {
            return HasConfigurableValue.class.isAssignableFrom(method.getReturnType());
        }

        private boolean isBuildable() {
            return Buildable.class.isAssignableFrom(method.getReturnType());
        }

        @Nullable
        @Override
        public Object call() {
            return valueSupplier.get();
        }

        @Nullable
        @Override
        public Object getUnprocessedValue() {
            return valueSupplier.get();
        }
    }
}
