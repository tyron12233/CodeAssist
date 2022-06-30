package com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.api.internal.tasks.compile.incremental.serialization.HierarchicalNameSerializer;

import java.util.function.Supplier;

public class DependentSetSerializer extends AbstractSerializer<DependentsSet> {
    private final Supplier<HierarchicalNameSerializer> hierarchicalNameSerializerSupplier;

    public DependentSetSerializer(Supplier<HierarchicalNameSerializer> hierarchicalNameSerializerSupplier) {
        this.hierarchicalNameSerializerSupplier = hierarchicalNameSerializerSupplier;
    }

    @Override
    public DependentsSet read(Decoder decoder) throws Exception {
        HierarchicalNameSerializer nameSerializer = hierarchicalNameSerializerSupplier.get();
        byte b = decoder.readByte();
        if (b == 0) {
            return DependentsSet.dependencyToAll(decoder.readString());
        }

        ImmutableSet.Builder<String> privateBuilder = ImmutableSet.builder();
        int count = decoder.readSmallInt();
        for (int i = 0; i < count; i++) {
            privateBuilder.add(nameSerializer.read(decoder));
        }

        ImmutableSet.Builder<String> accessibleBuilder = ImmutableSet.builder();
        count = decoder.readSmallInt();
        for (int i = 0; i < count; i++) {
            accessibleBuilder.add(nameSerializer.read(decoder));
        }

        ImmutableSet.Builder<GeneratedResource> resourceBuilder = ImmutableSet.builder();
        count = decoder.readSmallInt();
        for (int i = 0; i < count; i++) {
            GeneratedResource.Location location = GeneratedResource.Location.values()[decoder.readSmallInt()];
            String path = nameSerializer.read(decoder);
            resourceBuilder.add(new GeneratedResource(location, path));
        }
        return DependentsSet.dependents(privateBuilder.build(), accessibleBuilder.build(), resourceBuilder.build());
    }

    @Override
    public void write(Encoder encoder, DependentsSet dependentsSet) throws Exception {
        HierarchicalNameSerializer nameSerializer = hierarchicalNameSerializerSupplier.get();
        if (dependentsSet.isDependencyToAll()) {
            encoder.writeByte((byte) 0);
            encoder.writeString(dependentsSet.getDescription());
        } else {
            encoder.writeByte((byte) 1);
            encoder.writeSmallInt(dependentsSet.getPrivateDependentClasses().size());
            for (String className : dependentsSet.getPrivateDependentClasses()) {
                nameSerializer.write(encoder, className);
            }
            encoder.writeSmallInt(dependentsSet.getAccessibleDependentClasses().size());
            for (String className : dependentsSet.getAccessibleDependentClasses()) {
                nameSerializer.write(encoder, className);
            }
            encoder.writeSmallInt(dependentsSet.getDependentResources().size());
            for (GeneratedResource resource : dependentsSet.getDependentResources()) {
                encoder.writeSmallInt(resource.getLocation().ordinal());
                nameSerializer.write(encoder, resource.getPath());
            }
        }
    }
}

