package com.tyron.builder.tooling.internal.provider.serialization;

import java.io.Serializable;
import java.util.List;

public class SerializedPayload implements Serializable {
    private final List<byte[]> serializedModel;
    private final Object header;

    public SerializedPayload(Object header, List<byte[]> serializedModel) {
        this.header = header;
        this.serializedModel = serializedModel;
    }

    public Object getHeader() {
        return header;
    }

    public List<byte[]> getSerializedModel() {
        return serializedModel;
    }
}
