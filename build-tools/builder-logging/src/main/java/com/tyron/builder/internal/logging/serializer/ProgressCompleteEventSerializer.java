package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class ProgressCompleteEventSerializer implements Serializer<ProgressCompleteEvent> {
    @Override
    public void write(Encoder encoder, ProgressCompleteEvent event) throws Exception {
        encoder.writeSmallLong(event.getProgressOperationId().getId());
        encoder.writeLong(event.getTimestamp());
        encoder.writeString(event.getStatus());
        encoder.writeBoolean(event.isFailed());
    }

    @Override
    public ProgressCompleteEvent read(Decoder decoder) throws Exception {
        OperationIdentifier id = new OperationIdentifier(decoder.readSmallLong());
        long timestamp = decoder.readLong();
        String status = decoder.readString();
        boolean failed = decoder.readBoolean();
        return new ProgressCompleteEvent(id, timestamp, status, failed);
    }
}
