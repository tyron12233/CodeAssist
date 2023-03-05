package org.gradle.process.internal.worker.request;

import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Message;
import org.gradle.internal.serialize.Serializer;
import org.gradle.internal.serialize.SerializerRegistry;

public class RequestArgumentSerializers {
    private final SerializerRegistry registry = new DefaultSerializerRegistry();

    public Serializer<Object> getSerializer(ClassLoader defaultClassLoader) {
        registry.register(Object.class, new JavaObjectSerializer(defaultClassLoader));
        return registry.build(Object.class);
    }

    public <T> void register(Class<T> type, Serializer<T> serializer) {
        registry.register(type, serializer);
    }

    public static class JavaObjectSerializer implements Serializer<Object> {
        private final ClassLoader classLoader;

        public JavaObjectSerializer(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public Object read(Decoder decoder) throws Exception {
            return Message.receive(decoder.getInputStream(), classLoader);
        }

        @Override
        public void write(Encoder encoder, Object value) throws Exception {
            Message.send(value, encoder.getOutputStream());
        }
    }
}
