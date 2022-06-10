package org.gradle.process.internal.worker.request;

import org.gradle.internal.serialize.DefaultSerializer;
import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.serialize.SerializerRegistry;

public class RequestSerializerRegistry {
    public static SerializerRegistry create(ClassLoader classLoader, RequestArgumentSerializers argumentSerializers) {
        SerializerRegistry registry = new DefaultSerializerRegistry(false);
        registry.register(Request.class, new RequestSerializer(argumentSerializers.getSerializer(classLoader), false));
        return registry;
    }

    public static SerializerRegistry createDiscardRequestArg() {
        SerializerRegistry registry = new DefaultSerializerRegistry(false);
        registry.register(Request.class, new RequestSerializer(new DefaultSerializer<>(), true));
        return registry;
    }
}
