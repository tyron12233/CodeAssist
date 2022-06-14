package com.tyron.builder.internal.remote.internal;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.FlushableEncoder;
import com.tyron.builder.internal.serialize.kryo.KryoBackedDecoder;
import com.tyron.builder.internal.serialize.kryo.KryoBackedEncoder;

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
