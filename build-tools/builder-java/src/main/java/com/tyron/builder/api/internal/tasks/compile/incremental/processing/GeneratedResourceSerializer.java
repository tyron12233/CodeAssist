package com.tyron.builder.api.internal.tasks.compile.incremental.processing;

import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.BaseSerializerFactory;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;

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

