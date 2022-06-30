package com.tyron.builder.internal.serialize.kryo;

import com.tyron.builder.internal.serialize.*;

public class TypeSafeSerializer<T> implements StatefulSerializer<Object> {
    private final Class<T> type;
    private final StatefulSerializer<T> serializer;

    public TypeSafeSerializer(Class<T> type, StatefulSerializer<T> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    @Override
    public ObjectReader<Object> newReader(Decoder decoder) {
        final ObjectReader<T> reader = serializer.newReader(decoder);
        return new ObjectReader<Object>() {
            @Override
            public Object read() throws Exception {
                return reader.read();
            }
        };
    }

    @Override
    public ObjectWriter<Object> newWriter(Encoder encoder) {
        final ObjectWriter<T> writer = serializer.newWriter(encoder);
        return new ObjectWriter<Object>() {
            @Override
            public void write(Object value) throws Exception {
                writer.write(type.cast(value));
            }
        };
    }
}
