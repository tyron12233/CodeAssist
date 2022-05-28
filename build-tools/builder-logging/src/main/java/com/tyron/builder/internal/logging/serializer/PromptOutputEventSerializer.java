package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.logging.events.PromptOutputEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class PromptOutputEventSerializer implements Serializer<PromptOutputEvent> {
    @Override
    public void write(Encoder encoder, PromptOutputEvent value) throws Exception {
        encoder.writeLong(value.getTimestamp());
        encoder.writeString(value.getPrompt());
    }

    @Override
    public PromptOutputEvent read(Decoder decoder) throws Exception {
        return new PromptOutputEvent(decoder.readLong(), decoder.readString());
    }
}
