package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class IsolatedList extends AbstractListSnapshot<Isolatable<?>> implements Isolatable<List<Object>> {
    public static final IsolatedList EMPTY = new IsolatedList(ImmutableList.of());

    public IsolatedList(ImmutableList<Isolatable<?>> elements) {
        super(elements);
    }

    @Override
    public ValueSnapshot asSnapshot() {
        if (elements.isEmpty()) {
            return ListValueSnapshot.EMPTY;
        }
        ImmutableList.Builder<ValueSnapshot> builder = ImmutableList.builderWithExpectedSize(elements.size());
        for (Isolatable<?> element : elements) {
            builder.add(element.asSnapshot());
        }
        return new ListValueSnapshot(builder.build());
    }

    @Override
    public List<Object> isolate() {
        List<Object> list = new ArrayList<Object>(elements.size());
        for (Isolatable<?> element : elements) {
            list.add(element.isolate());
        }
        return list;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        return null;
    }
}

