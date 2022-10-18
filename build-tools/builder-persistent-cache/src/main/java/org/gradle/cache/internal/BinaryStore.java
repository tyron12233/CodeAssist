package org.gradle.cache.internal;

import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.Closeable;
import java.io.IOException;

public interface BinaryStore {
    void write(WriteAction write);

    //done writing data, release any resources
    BinaryData done();

    interface WriteAction {
        void write(Encoder encoder) throws IOException;
    }

    interface ReadAction<T> {
        T read(Decoder decoder) throws IOException;
    }

    interface BinaryData extends Closeable {
        <T> T read(ReadAction<T> readAction);
    }
}
