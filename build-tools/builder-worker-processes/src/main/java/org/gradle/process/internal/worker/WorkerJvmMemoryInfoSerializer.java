package org.gradle.process.internal.worker;

import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;
import org.gradle.internal.serialize.SerializerRegistry;
import org.gradle.process.internal.health.memory.JvmMemoryStatus;
import org.gradle.process.internal.health.memory.JvmMemoryStatusSnapshot;

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
