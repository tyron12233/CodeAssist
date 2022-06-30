package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class ProgressEventSerializer implements Serializer<ProgressEvent> {
    @Override
    public void write(Encoder encoder, ProgressEvent event) throws Exception {
        encoder.writeSmallLong(event.getProgressOperationId().getId());
        encoder.writeString(event.getStatus());
        encoder.writeBoolean(event.isFailing());
    }

    @Override
    public ProgressEvent read(Decoder decoder) throws Exception {
        OperationIdentifier id = new OperationIdentifier(decoder.readSmallLong());
        String status = decoder.readString();
        boolean failing = decoder.readBoolean();
        return new ProgressEvent(id, status, failing);
    }
}
