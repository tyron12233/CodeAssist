package org.gradle.api.internal.tasks.compile.incremental.processing;

import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.BaseSerializerFactory;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;
import org.gradle.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;

public class GeneratedResourceSerializer extends AbstractSerializer<GeneratedResource> {
    private static final Serializer<GeneratedResource.Location> LOCATION_SERIALIZER = new BaseSerializerFactory().getSerializerFor(GeneratedResource.Location.class);
    private final Serializer<String> stringSerializer;

    public GeneratedResourceSerializer(Serializer<String> stringSerializer) {
        this.stringSerializer = stringSerializer;
    }

    @Override
    public GeneratedResource read(Decoder decoder) throws Exception {
        return new GeneratedResource(LOCATION_SERIALIZER.read(decoder), stringSerializer.read(decoder));
    }

    @Override
    public void write(Encoder encoder, GeneratedResource value) throws Exception {
        LOCATION_SERIALIZER.write(encoder, value.getLocation());
        stringSerializer.write(encoder, value.getPath());
    }
}

