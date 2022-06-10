package org.gradle.internal.logging.serializer;

import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

public class SpanSerializer implements Serializer<StyledTextOutputEvent.Span> {
    private final Serializer<StyledTextOutput.Style> styleSerializer;

    public SpanSerializer(Serializer<StyledTextOutput.Style> styleSerializer) {
        this.styleSerializer = styleSerializer;
    }

    @Override
    public void write(Encoder encoder, StyledTextOutputEvent.Span value) throws Exception {
        styleSerializer.write(encoder, value.getStyle());
        encoder.writeString(value.getText());
    }

    @Override
    public StyledTextOutputEvent.Span read(Decoder decoder) throws Exception {
        return new StyledTextOutputEvent.Span(styleSerializer.read(decoder), decoder.readString());
    }
}
