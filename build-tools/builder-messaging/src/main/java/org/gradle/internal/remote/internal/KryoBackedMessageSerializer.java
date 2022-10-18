package org.gradle.internal.remote.internal;

import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.FlushableEncoder;
import org.gradle.internal.serialize.kryo.KryoBackedDecoder;
import org.gradle.internal.serialize.kryo.KryoBackedEncoder;

import java.io.InputStream;
import java.io.OutputStream;

public class KryoBackedMessageSerializer implements MessageSerializer {
    @Override
    public Decoder newDecoder(InputStream inputStream) {
        return new KryoBackedDecoder(inputStream);
    }

    @Override
    public FlushableEncoder newEncoder(OutputStream outputStream) {
        return new KryoBackedEncoder(outputStream);
    }
}
