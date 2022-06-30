package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.classloader.ClassLoaderUtils;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.serialize.kryo.KryoBackedDecoder;
import com.tyron.builder.internal.serialize.kryo.KryoBackedEncoder;
import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class DefaultActionExecutionSpecFactory implements ActionExecutionSpecFactory {
    private final IsolatableFactory isolatableFactory;
    private final IsolatableSerializerRegistry serializerRegistry;

    public DefaultActionExecutionSpecFactory(IsolatableFactory isolatableFactory, IsolatableSerializerRegistry serializerRegistry) {
        this.isolatableFactory = isolatableFactory;
        this.serializerRegistry = serializerRegistry;
    }

    @Override
    public <T extends WorkParameters> TransportableActionExecutionSpec newTransportableSpec(IsolatedParametersActionExecutionSpec<T> spec) {
        return new TransportableActionExecutionSpec(spec.getImplementationClass().getName(), serialize(spec.getIsolatedParams()), spec.getClassLoaderStructure(), spec.getBaseDir(), spec.isInternalServicesRequired());
    }

    @Override
    public <T extends WorkParameters> IsolatedParametersActionExecutionSpec<T> newIsolatedSpec(String displayName, Class<? extends WorkAction<T>> implementationClass, T params, WorkerRequirement workerRequirement, boolean usesInternalServices) {
        ClassLoaderStructure classLoaderStructure = workerRequirement instanceof IsolatedClassLoaderWorkerRequirement ? ((IsolatedClassLoaderWorkerRequirement) workerRequirement).getClassLoaderStructure() : null;
        String actionImplementationClassName = implementationClass.equals(AdapterWorkAction.class) ? ((AdapterWorkParameters) params).getImplementationClassName() : implementationClass.getName();
        return new IsolatedParametersActionExecutionSpec<T>(implementationClass, displayName, actionImplementationClassName, isolatableFactory.isolate(params), classLoaderStructure, workerRequirement.getWorkerDirectory(), usesInternalServices);
    }

    @Override
    public <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(IsolatedParametersActionExecutionSpec<T> spec) {
        T params = Cast.uncheckedCast(spec.getIsolatedParams().isolate());
        return new SimpleActionExecutionSpec<T>(spec.getImplementationClass(), params, spec.isInternalServicesRequired());
    }

    @Override
    public <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(TransportableActionExecutionSpec spec) {
        T params = Cast.uncheckedCast(deserialize(spec.getSerializedParameters()).isolate());
        return new SimpleActionExecutionSpec<T>(Cast.uncheckedCast(fromClassName(spec.getImplementationClassName())), params, spec.isInternalServicesRequired());
    }

    Class<?> fromClassName(String className) {
        try {
            return ClassLoaderUtils.classFromContextLoader(className);
        } catch (Exception e) {
            throw new WorkSerializationException("Could not deserialize unit of work.", e);
        }
    }

    private byte[] serialize(Isolatable<?> isolatable) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        KryoBackedEncoder encoder = new KryoBackedEncoder(outputStream);
        try {
            serializerRegistry.writeIsolatable(encoder, isolatable);
            encoder.flush();
        } catch (Exception e) {
            throw new WorkSerializationException("Could not serialize unit of work.", e);
        }
        return outputStream.toByteArray();
    }

    private Isolatable<?> deserialize(byte[] bytes) {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        KryoBackedDecoder decoder = new KryoBackedDecoder(inputStream);
        try {
            return serializerRegistry.readIsolatable(decoder);
        } catch (Exception e) {
            throw new WorkSerializationException("Could not deserialize unit of work.", e);
        }
    }
}
