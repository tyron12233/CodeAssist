package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;
import java.lang.reflect.Array;

public class IsolatedArray extends AbstractArraySnapshot<Isolatable<?>> implements Isolatable<Object[]> {
    public static final IsolatedArray EMPTY = empty(Object.class);
    private final Class<?> arrayType;

    public IsolatedArray(ImmutableList<Isolatable<?>> elements, Class<?> arrayType) {
        super(elements);
        this.arrayType = arrayType;
    }

    @Override
    public ValueSnapshot asSnapshot() {
        if (elements.isEmpty()) {
            return ArrayValueSnapshot.EMPTY;
        }
        ImmutableList.Builder<ValueSnapshot> builder = ImmutableList.builderWithExpectedSize(elements.size());
        for (Isolatable<?> element : elements) {
            builder.add(element.asSnapshot());
        }
        return new ArrayValueSnapshot(builder.build());
    }

    @Override
    public Object[] isolate() {
        Object[] toReturn = (Object[]) Array.newInstance(arrayType, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            Isolatable<?> element = elements.get(i);
            toReturn[i] = element.isolate();
        }
        return toReturn;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        return null;
    }

    public Class<?> getArrayType() {
        return arrayType;
    }

    public static IsolatedArray empty(Class<?> arrayType) {
        return new IsolatedArray(ImmutableList.of(), arrayType);
    }
}

