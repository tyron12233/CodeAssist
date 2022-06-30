package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.Transformer;
import com.tyron.builder.internal.serialize.Serializer;

/**
 * A {@link CompileOperation} that does not extract or persist any data.
 */
public class NoDataCompileOperation implements CompileOperation<Object> {

    private final String id;
    private final String stage;
    private final Transformer transformer;

    public NoDataCompileOperation(String id, String stage, Transformer transformer) {
        this.id = id;
        this.stage = stage;
        this.transformer = transformer;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getStage() {
        return stage;
    }

    @Override
    public Transformer getTransformer() {
        return transformer;
    }

    @Override
    public Object getExtractedData() {
        return null;
    }

    @Override
    public Serializer<Object> getDataSerializer() {
        return null;
    }
}
