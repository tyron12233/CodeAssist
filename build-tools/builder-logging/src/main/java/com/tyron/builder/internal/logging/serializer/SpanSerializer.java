package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

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
