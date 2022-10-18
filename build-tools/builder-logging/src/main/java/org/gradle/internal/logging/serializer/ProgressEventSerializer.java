package org.gradle.internal.logging.serializer;

import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

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
