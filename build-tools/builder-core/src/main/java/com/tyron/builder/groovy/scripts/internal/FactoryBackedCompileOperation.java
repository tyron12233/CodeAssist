package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.Transformer;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.serialize.Serializer;

public class FactoryBackedCompileOperation<T> implements CompileOperation<T> {

    private final String id;
    private final String stage;
    private final Transformer transformer;
    private final Factory<T> dataFactory;
    private final Serializer<T> serializer;

    public FactoryBackedCompileOperation(String id, String stage, Transformer transformer, Factory<T> dataFactory, Serializer<T> serializer) {
        this.id = id;
        this.stage = stage;
        this.transformer = transformer;
        this.dataFactory = dataFactory;
        this.serializer = serializer;
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
    public T getExtractedData() {
        return dataFactory.create();
    }

    @Override
    public Serializer<T> getDataSerializer() {
        return serializer;
    }
}
