package com.tyron.builder.api.internal.tasks.compile.incremental.deps;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.internal.serialize.IntSetSerializer;
import com.tyron.builder.internal.serialize.InterningStringSerializer;
import com.tyron.builder.internal.serialize.SetSerializer;

import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

/**
 * An immutable set of details extracted from a class file.
 */
public class ClassAnalysis {
    private final String className;
    private final Set<String> privateClassDependencies;
    private final Set<String> accessibleClassDependencies;
    private final String dependencyToAllReason;
    private final IntSet constants;

    public ClassAnalysis(String className, Set<String> privateClassDependencies, Set<String> accessibleClassDependencies, String dependencyToAllReason, IntSet constants) {
        this.className = className;
        this.privateClassDependencies = ImmutableSet.copyOf(privateClassDependencies);
        this.accessibleClassDependencies = ImmutableSet.copyOf(accessibleClassDependencies);
        this.dependencyToAllReason = dependencyToAllReason;
        this.constants = constants.isEmpty() ? IntSets.EMPTY_SET : constants;
    }

    public String getClassName() {
        return className;
    }

    public Set<String> getPrivateClassDependencies() {
        return privateClassDependencies;
    }

    public Set<String> getAccessibleClassDependencies() {
        return accessibleClassDependencies;
    }

    public IntSet getConstants() {
        return constants;
    }

    public String getDependencyToAllReason() {
        return dependencyToAllReason;
    }

    public static class Serializer extends AbstractSerializer<ClassAnalysis> {

        private final StringInterner interner;
        private final SetSerializer<String> stringSetSerializer;

        public Serializer(StringInterner interner) {
            stringSetSerializer = new SetSerializer<>(new InterningStringSerializer(interner), false);
            this.interner = interner;
        }

        @Override
        public ClassAnalysis read(Decoder decoder) throws Exception {
            String className = interner.intern(decoder.readString());
            String dependencyToAllReason = decoder.readNullableString();
            Set<String> privateClasses = stringSetSerializer.read(decoder);
            Set<String> accessibleClasses = stringSetSerializer.read(decoder);
            IntSet constants = IntSetSerializer.INSTANCE.read(decoder);
            return new ClassAnalysis(className, privateClasses, accessibleClasses, dependencyToAllReason, constants);
        }

        @Override
        public void write(Encoder encoder, ClassAnalysis value) throws Exception {
            encoder.writeString(value.getClassName());
            encoder.writeNullableString(value.getDependencyToAllReason());
            stringSetSerializer.write(encoder, value.getPrivateClassDependencies());
            stringSetSerializer.write(encoder, value.getAccessibleClassDependencies());
            IntSetSerializer.INSTANCE.write(encoder, value.getConstants());
        }

    }
}
