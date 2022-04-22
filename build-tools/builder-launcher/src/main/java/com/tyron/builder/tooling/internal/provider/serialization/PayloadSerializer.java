package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.io.StreamByteBuffer;

import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@ThreadSafe
public class PayloadSerializer {
    private final PayloadClassLoaderRegistry classLoaderRegistry;

    public PayloadSerializer(PayloadClassLoaderRegistry registry) {
        classLoaderRegistry = registry;
    }

    public SerializedPayload serialize(@Nullable Object payload) {
        if (payload == null) {
            return new SerializedPayload(null, Collections.<byte[]>emptyList());
        }

        final SerializeMap map = classLoaderRegistry.newSerializeSession();
        try {
            StreamByteBuffer buffer = new StreamByteBuffer();
            final ObjectOutputStream objectStream = new PayloadSerializerObjectOutputStream(buffer.getOutputStream(), map);

            try {
                objectStream.writeObject(payload);
            } finally {
                IOUtils.closeQuietly(objectStream);
            }

            Map<Short, ClassLoaderDetails> classLoaders = new HashMap<Short, ClassLoaderDetails>();
            map.collectClassLoaderDefinitions(classLoaders);
            return new SerializedPayload(classLoaders, buffer.readAsListOfByteArrays());
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    public @Nullable Object deserialize(SerializedPayload payload) {
        if (payload.getSerializedModel().isEmpty()) {
            return null;
        }

        final DeserializeMap map = classLoaderRegistry.newDeserializeSession();
        try {
            final Map<Short, ClassLoaderDetails> classLoaderDetails = Cast.uncheckedNonnullCast(payload.getHeader());
            StreamByteBuffer buffer = StreamByteBuffer.of(payload.getSerializedModel());
            final ObjectInputStream objectStream = new PayloadSerializerObjectInputStream(buffer.getInputStream(), getClass().getClassLoader(), classLoaderDetails, map);
            return objectStream.readObject();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

}
