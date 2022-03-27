package com.tyron.builder.api.internal.io;


import java.nio.Buffer;

public class BufferCaster {
    /**
     * Without this cast, when the code compiled by Java 9+ is executed on Java 8, it will throw
     * java.lang.NoSuchMethodError: Method flip()Ljava/nio/ByteBuffer; does not exist in class java.nio.ByteBuffer
     */
    @SuppressWarnings("RedundantCast")
    public static <T extends Buffer> Buffer cast(T byteBuffer) {
        return (Buffer) byteBuffer;
    }
}