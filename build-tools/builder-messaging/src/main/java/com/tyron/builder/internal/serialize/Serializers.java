package com.tyron.builder.internal.serialize;

public class Serializers {
    public static <T> StatefulSerializer<T> stateful(final Serializer<T> serializer) {
        return new StatefulSerializerAdapter<T>(serializer);
    }

    private static class StatefulSerializerAdapter<T> implements StatefulSerializer<T> {
        private final Serializer<T> serializer;

        public StatefulSerializerAdapter(Serializer<T> serializer) {
            this.serializer = serializer;
        }

        @Override
        public ObjectReader<T> newReader(final Decoder decoder) {
            return new ObjectReader<T>() {
                @Override
                public T read() throws Exception {
                    return serializer.read(decoder);
                }
            };
        }

        @Override
        public ObjectWriter<T> newWriter(final Encoder encoder) {
            return new ObjectWriter<T>() {
                @Override
                public void write(T value) throws Exception {
                    serializer.write(encoder, value);
                }
            };
        }
    }

    public static <T> Serializer<T> constant(final T instance) {
        return new Serializer<T>() {
            @Override
            public T read(Decoder decoder) {
                return instance;
            }

            @Override
            public void write(Encoder encoder, T value) {
                if (value != instance) {
                    throw new IllegalArgumentException("Cannot serialize constant value: " + value);
                }
            }
        };
    }
}
