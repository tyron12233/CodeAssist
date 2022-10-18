package org.gradle.internal.remote.internal;

import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.FlushableEncoder;

import java.io.InputStream;
import java.io.OutputStream;

public interface MessageSerializer {
    /**
     * Creates a decoder that reads from the given input stream. Note that the implementation may perform buffering, and may consume any or all of the
     * content from the given input stream.
     */
    Decoder newDecoder(InputStream inputStream);

    /**
     * Creates an encoder that writes the given output stream. Note that the implementation may perform buffering.
     */
    FlushableEncoder newEncoder(OutputStream outputStream);
}
