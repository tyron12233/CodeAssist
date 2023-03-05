package org.gradle.internal.logging.serializer;

import org.gradle.internal.logging.events.PromptOutputEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

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
