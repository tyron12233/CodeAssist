package com.tyron.builder.internal.serialize;

import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.cache.StringInterner;

public class InterningStringSerializer extends AbstractSerializer<String> {
    private final StringInterner stringInterner;

    public InterningStringSerializer(StringInterner stringInterner) {
        this.stringInterner = stringInterner;
    }

    @Override
    public String read(Decoder decoder) throws Exception {
        return stringInterner.intern(decoder.readString());
    }

    @Override
    public void write(Encoder encoder, String value) throws Exception {
        encoder.writeString(value);
    }
}


