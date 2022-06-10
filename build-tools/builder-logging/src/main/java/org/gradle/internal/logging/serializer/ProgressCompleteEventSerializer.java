package org.gradle.internal.logging.serializer;

import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

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
