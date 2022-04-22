package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.DefaultSerializer;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

import java.util.ArrayList;
import java.util.List;

public class SerializedPayloadSerializer implements Serializer<SerializedPayload> {
    private final Serializer<Object> javaSerializer = new DefaultSerializer<Object>();

    @Override
    public void write(Encoder encoder, SerializedPayload value) throws Exception {
        javaSerializer.write(encoder, value.getHeader());
        encoder.writeSmallInt(value.getSerializedModel().size());
        for (byte[] bytes : value.getSerializedModel()) {
            encoder.writeBinary(bytes);
        }
    }

    @Override
    public SerializedPayload read(Decoder decoder) throws Exception {
        Object header = javaSerializer.read(decoder);
        int count = decoder.readSmallInt();
        List<byte[]> chunks = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            chunks.add(decoder.readBinary());
        }
        return new SerializedPayload(header, chunks);
    }
}
