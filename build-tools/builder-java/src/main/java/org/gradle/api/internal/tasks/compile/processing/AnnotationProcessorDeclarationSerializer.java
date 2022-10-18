package org.gradle.api.internal.tasks.compile.processing;

import org.gradle.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.EOFException;

class AnnotationProcessorDeclarationSerializer implements org.gradle.internal.serialize.Serializer<AnnotationProcessorDeclaration> {
    public static final AnnotationProcessorDeclarationSerializer INSTANCE = new AnnotationProcessorDeclarationSerializer();

    private AnnotationProcessorDeclarationSerializer() {
    }

    @Override
    public AnnotationProcessorDeclaration read(Decoder decoder) throws EOFException, Exception {
        String name = decoder.readString();
        IncrementalAnnotationProcessorType type = IncrementalAnnotationProcessorType.values()[decoder.readSmallInt()];
        return new AnnotationProcessorDeclaration(name, type);
    }

    @Override
    public void write(Encoder encoder, AnnotationProcessorDeclaration value) throws Exception {
        encoder.writeString(value.getClassName());
        encoder.writeSmallInt(value.getType().ordinal());
    }
}
