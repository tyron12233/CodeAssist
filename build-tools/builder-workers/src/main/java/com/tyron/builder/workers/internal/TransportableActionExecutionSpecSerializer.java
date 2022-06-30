package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

import java.io.File;

public class TransportableActionExecutionSpecSerializer implements Serializer<TransportableActionExecutionSpec> {
    private static final byte FLAT = (byte) 0;
    private static final byte HIERARCHICAL = (byte) 1;

    private final Serializer<HierarchicalClassLoaderStructure> hierarchicalClassLoaderStructureSerializer = new HierarchicalClassLoaderStructureSerializer();

    @Override
    public void write(Encoder encoder, TransportableActionExecutionSpec spec) throws Exception {
        encoder.writeString(spec.getImplementationClassName());
        encoder.writeBoolean(spec.isInternalServicesRequired());
        encoder.writeString(spec.getBaseDir().getAbsolutePath());
        encoder.writeBinary(spec.getSerializedParameters());
        if (spec.getClassLoaderStructure() instanceof HierarchicalClassLoaderStructure) {
            encoder.writeByte(HIERARCHICAL);
            hierarchicalClassLoaderStructureSerializer.write(encoder, (HierarchicalClassLoaderStructure) spec.getClassLoaderStructure());
        } else if (spec.getClassLoaderStructure() instanceof FlatClassLoaderStructure) {
            encoder.writeByte(FLAT);
            // If the classloader structure is flat, there's no need to send the classpath
        } else {
            throw new IllegalArgumentException("Unknown classloader structure type: " + spec.getClassLoaderStructure().getClass().getSimpleName());
        }
    }

    @Override
    public TransportableActionExecutionSpec read(Decoder decoder) throws Exception {
        String implementationClassName = decoder.readString();
        boolean usesInternalServices = decoder.readBoolean();
        String baseDirPath = decoder.readString();
        byte[] serializedParameters = decoder.readBinary();
        byte classLoaderStructureTag = decoder.readByte();
        ClassLoaderStructure classLoaderStructure;
        switch (classLoaderStructureTag) {
            case FLAT:
                classLoaderStructure = new FlatClassLoaderStructure(null);
                break;
            case HIERARCHICAL:
                classLoaderStructure = hierarchicalClassLoaderStructureSerializer.read(decoder);
                break;
            default:
                throw new IllegalArgumentException("Unexpected payload type.");
        }
        return new TransportableActionExecutionSpec(implementationClassName, serializedParameters, classLoaderStructure, new File(baseDirPath), usesInternalServices);
    }
}
