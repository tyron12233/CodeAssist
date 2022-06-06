package com.tyron.builder.internal.instantiation.generator;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.internal.instantiation.InstanceFactory;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.logging.text.TreeFormatter;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.reflect.JavaReflectionUtil;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceLookup;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;

/**
 * An {@link Instantiator} that applies dependency injection, delegating to a {@link ConstructorSelector} to decide which constructor to use to create instances.
 */
class DependencyInjectingInstantiator implements InstanceGenerator {
    private static final DefaultServiceRegistry NO_SERVICES = new DefaultServiceRegistry("registry with no services");
    private final ServiceLookup services;
    private final ConstructorSelector constructorSelector;

    public DependencyInjectingInstantiator(ConstructorSelector constructorSelector, ServiceLookup services) {
        this.services = services;
        this.constructorSelector = constructorSelector;
    }

    @Override
    public <T> T newInstanceWithDisplayName(Class<? extends T> type, Describable displayName, Object... parameters) {
        return doCreate(type, displayName, parameters);
    }

    @Override
    public <T> T newInstance(Class<? extends T> type, Object... parameters) {
        return doCreate(type, null, parameters);
    }

    @NotNull
    private <T> T doCreate(Class<? extends T> type, @Nullable Describable displayName, Object[] parameters) {
        try {
            ClassGenerator.GeneratedConstructor<? extends T> constructor = constructorSelector.forParams(type, parameters);
            Object[] resolvedParameters = convertParameters(type, constructor, services, parameters);
            try {
                return constructor.newInstance(services, this, displayName, resolvedParameters);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } catch (Throwable t) {
            throw new ObjectInstantiationException(type, t);
        }
    }

    public <T> InstanceFactory<T> factoryFor(final Class<T> type) {
        final ClassGenerator.GeneratedConstructor<? extends T> constructor = constructorSelector.forType(type);
        return new InstanceFactory<T>() {
            @Override
            public boolean serviceInjectionTriggeredByAnnotation(Class<? extends Annotation> injectAnnotation) {
                return constructor.serviceInjectionTriggeredByAnnotation(injectAnnotation);
            }

            @Override
            public boolean requiresService(Class<?> serviceType) {
                return constructor.requiresService(serviceType);
            }

            @Override
            public T newInstance(ServiceLookup services, Object... parameters) {
                try {
                    Object[] resolvedParameters = convertParameters(type, constructor, services, parameters);
                    try {
                        return constructor.newInstance(services, DependencyInjectingInstantiator.this, null, resolvedParameters);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                } catch (Throwable t) {
                    throw new ObjectInstantiationException(type, t);
                }
            }

            @Override
            public T newInstance(Object... params) {
                return newInstance(NO_SERVICES, params);
            }
        };
    }

    private Object[] convertParameters(Class<?> type, ClassGenerator.GeneratedConstructor<?> constructor, ServiceLookup services, Object[] parameters) {
        constructorSelector.vetoParameters(constructor, parameters);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length < parameters.length) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Too many parameters provided for constructor for type ");
            formatter.appendType(type);
            formatter.append(String.format(". Expected %s, received %s.", parameterTypes.length, parameters.length));
            throw new IllegalArgumentException(formatter.toString());
        }
        if (parameterTypes.length == parameters.length) {
            // No services to be mixed in
            return verifyParameters(constructor, parameters);
        } else {
            return addServicesToParameters(type, constructor, services, parameters);
        }
    }

    private Object[] verifyParameters(ClassGenerator.GeneratedConstructor<?> constructor, Object[] parameters) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> targetType = parameterTypes[i];
            Object currentParameter = parameters[i];
            if (targetType.isPrimitive()) {
                if (currentParameter == null) {
                    nullPrimitiveType(i, targetType);
                }
                targetType = JavaReflectionUtil.getWrapperTypeForPrimitiveType(targetType);
            } else if (currentParameter == null) {
                // Null is ok if the ConstructorSelector says it's ok
                continue;
            }
            if (!targetType.isInstance(currentParameter)) {
                TreeFormatter formatter = new TreeFormatter();
                formatter.node("Unable to determine constructor argument #" + (i + 1) + ": value ");
                formatter.appendValue(currentParameter);
                formatter.append(" not assignable to type ");
                formatter.appendType(parameterTypes[i]);
                formatter.append(".");
                throw new IllegalArgumentException(formatter.toString());
            }
        }
        return parameters;
    }

    private void nullPrimitiveType(int index, Class<?> paramType) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Unable to determine constructor argument #" + (index + 1) + ": null value is not assignable to type ");
        formatter.appendType(paramType);
        formatter.append(".");
        throw new IllegalArgumentException(formatter.toString());
    }

    private Object[] addServicesToParameters(Class<?> type, ClassGenerator.GeneratedConstructor<?> constructor, ServiceLookup services, Object[] parameters) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Type[] genericTypes = constructor.getGenericParameterTypes();
        Object[] resolvedParameters = new Object[parameterTypes.length];
        int pos = 0;
        for (int i = 0; i < resolvedParameters.length; i++) {
            Class<?> targetType = parameterTypes[i];
            Type serviceType = genericTypes[i];
            if (pos < parameters.length) {
                Object parameter = parameters[pos];
                if (targetType.isPrimitive()) {
                    if (parameter == null) {
                        nullPrimitiveType(i, targetType);
                    }
                    targetType = JavaReflectionUtil.getWrapperTypeForPrimitiveType(targetType);
                }
                if (parameter == null || targetType.isInstance(parameter)) {
                    resolvedParameters[i] = parameter;
                    pos++;
                    continue;
                }
            }
            Object service = services.find(serviceType);
            if (service != null) {
                resolvedParameters[i] = service;
                continue;
            }
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Unable to determine constructor argument #" + (i + 1) + ": ");
            if (pos < parameters.length) {
                formatter.append("value ");
                formatter.appendValue(parameters[pos]);
                formatter.append(" is not assignable to type ");
                formatter.appendType(parameterTypes[i]);
            } else {
                formatter.append("missing parameter of type ");
                formatter.appendType(parameterTypes[i]);
            }
            formatter.append(", or no service of type ");
            formatter.appendType(serviceType);
            formatter.append(".");
            throw new IllegalArgumentException(formatter.toString());
        }

        if (pos != parameters.length) {
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Unexpected parameter provided for constructor for ");
            formatter.appendType(type);
            throw new IllegalArgumentException(formatter.toString());
        }

        return resolvedParameters;
    }
}
