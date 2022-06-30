package com.tyron.builder.process.internal.worker;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.DefaultSerializerRegistry;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.serialize.SerializerRegistry;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatusSnapshot;

import java.io.EOFException;

public class WorkerJvmMemoryInfoSerializer {
    public static SerializerRegistry create() {
        DefaultSerializerRegistry registry = new DefaultSerializerRegistry(false);

        registry.register(JvmMemoryStatus.class, new JvmMemoryStatusSerializer());
        return registry;
    }

    private static class JvmMemoryStatusSerializer implements Serializer<JvmMemoryStatus> {
        @Override
        public JvmMemoryStatus read(Decoder decoder) throws EOFException, Exception {
            long committedMemory = decoder.readLong();
            long maxMemory = decoder.readLong();
            return new JvmMemoryStatusSnapshot(maxMemory, committedMemory);
        }

        @Override
        public void write(Encoder encoder, JvmMemoryStatus jvmMemoryStatus) throws Exception {
            encoder.writeLong(jvmMemoryStatus.getCommittedMemory());
            encoder.writeLong(jvmMemoryStatus.getMaxMemory());
        }
    }
}
