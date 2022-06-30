package com.tyron.builder.process.internal.worker.request;

import com.tyron.builder.internal.serialize.DefaultSerializer;
import com.tyron.builder.internal.serialize.DefaultSerializerRegistry;
import com.tyron.builder.internal.serialize.SerializerRegistry;

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
