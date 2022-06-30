package com.tyron.builder.process.internal.worker.request;

import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.DefaultBuildOperationRef;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class RequestSerializer implements Serializer<Request> {
    private final Serializer<Object> argSerializer;
    private final boolean skipIncomingArg;

    public RequestSerializer(Serializer<Object> argSerializer, boolean skipIncomingArg) {
        this.argSerializer = argSerializer;
        this.skipIncomingArg = skipIncomingArg;
    }

    @Override
    public void write(Encoder encoder, Request request) throws Exception {
        encoder.encodeChunked(target -> {
            Object object = request.getArg();
            argSerializer.write(target, object);
        });

        encoder.writeLong(request.getBuildOperation().getId().getId());
        OperationIdentifier parentId = request.getBuildOperation().getParentId();
        if (parentId != null) {
            encoder.writeBoolean(true);
            encoder.writeLong(parentId.getId());
        } else {
            encoder.writeBoolean(false);
        }
    }

    @Override
    public Request read(Decoder decoder) throws Exception {
        Object arg;
        if (skipIncomingArg) {
            decoder.skipChunked();
            arg = null;
        } else {
            arg = decoder.decodeChunked(argSerializer::read);
        }

        OperationIdentifier id = new OperationIdentifier(decoder.readLong());
        BuildOperationRef buildOperation;
        if (decoder.readBoolean()) {
            buildOperation = new DefaultBuildOperationRef(id, new OperationIdentifier(decoder.readLong()));
        } else {
            buildOperation = new DefaultBuildOperationRef(id, null);
        }

        return new Request(arg, buildOperation);
    }

}
