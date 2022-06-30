package com.tyron.builder.internal.serialize;

import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;

public class ListSerializer<T> extends AbstractCollectionSerializer<T, List<T>> implements Serializer<List<T>> {

    public ListSerializer(Serializer<T> entrySerializer) {
        super(entrySerializer);
    }

    @Override
    protected List<T> createCollection(int size) {
        if (size == 0) {
            return Collections.emptyList();
        }
        return Lists.newArrayListWithCapacity(size);
    }
}
