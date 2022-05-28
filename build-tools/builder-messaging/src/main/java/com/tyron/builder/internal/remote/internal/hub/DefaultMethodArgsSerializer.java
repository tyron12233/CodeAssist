package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.serialize.SerializerRegistry;

import java.util.List;

class DefaultMethodArgsSerializer implements MethodArgsSerializer {
    private static final Object[] ZERO_ARGS = new Object[0];
    private final List<SerializerRegistry> serializerRegistries;
    private final MethodArgsSerializer defaultArgsSerializer;

    public DefaultMethodArgsSerializer(List<SerializerRegistry> serializerRegistries, MethodArgsSerializer defaultArgsSerializer) {
        this.serializerRegistries = serializerRegistries;
        this.defaultArgsSerializer = defaultArgsSerializer;
    }

    @Override
    public Serializer<Object[]> forTypes(Class<?>[] types) {
        if (types.length == 0) {
            return new EmptyArraySerializer();
        }
        SerializerRegistry selected = null;
        for (SerializerRegistry serializerRegistry : serializerRegistries) {
            if (serializerRegistry.canSerialize(types[0])) {
                selected = serializerRegistry;
                break;
            }
        }
        if (selected == null) {
            return defaultArgsSerializer.forTypes(types);
        }

        final Serializer<Object>[] serializers = Cast.uncheckedNonnullCast(new Serializer<?>[types.length]);
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            serializers[i] = Cast.uncheckedNonnullCast(selected.build(type));
        }
        return new ArraySerializer(serializers);
    }

    private static class ArraySerializer implements Serializer<Object[]> {
        private final Serializer<Object>[] serializers;

        ArraySerializer(Serializer<Object>[] serializers) {
            this.serializers = serializers;
        }

        @Override
        public Object[] read(Decoder decoder) throws Exception {
            Object[] result = new Object[serializers.length];
            for (int i = 0; i < serializers.length; i++) {
                result[i] = serializers[i].read(decoder);
            }
            return result;
        }

        @Override
        public void write(Encoder encoder, Object[] value) throws Exception {
            for (int i = 0; i < value.length; i++) {
                serializers[i].write(encoder, value[i]);
            }
        }
    }

    private static class EmptyArraySerializer implements Serializer<Object[]> {
        @Override
        public Object[] read(Decoder decoder) {
            return ZERO_ARGS;
        }

        @Override
        public void write(Encoder encoder, Object[] value) {
        }
    }
}
