package org.gradle.internal.snapshot.impl;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

public class IsolatedSet extends AbstractSetSnapshot<Isolatable<?>> implements Isolatable<Set<Object>> {
    public IsolatedSet(ImmutableSet<Isolatable<?>> elements) {
        super(elements);
    }

    @Override
    public ValueSnapshot asSnapshot() {
        ImmutableSet.Builder<ValueSnapshot> builder = ImmutableSet.builderWithExpectedSize(elements.size());
        for (Isolatable<?> element : elements) {
            builder.add(element.asSnapshot());
        }
        return new SetValueSnapshot(builder.build());
    }

    @Override
    public Set<Object> isolate() {
        Set<Object> set = new LinkedHashSet<>(elements.size());
        for (Isolatable<?> element : elements) {
            set.add(element.isolate());
        }
        return set;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        return null;
    }
}
