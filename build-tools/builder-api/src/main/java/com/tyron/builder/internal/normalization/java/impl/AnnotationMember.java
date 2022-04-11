package com.tyron.builder.internal.normalization.java.impl;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.SortedSet;

public class AnnotationMember extends Member implements Comparable<AnnotationMember> {

    private final SortedSet<AnnotationValue<?>> values = Sets.newTreeSet();
    private final boolean visible;

    public AnnotationMember(String name, boolean visible) {
        super(name);
        this.visible = visible;
    }

    public SortedSet<AnnotationValue<?>> getValues() {
        return ImmutableSortedSet.copyOf(values);
    }

    public void addValue(AnnotationValue<?> value) {
        values.add(value);
    }

    public void addValues(Collection<AnnotationValue<?>> values) {
        this.values.addAll(values);
    }

    public boolean isVisible() {
        return visible;
    }

    protected ComparisonChain compare(AnnotationMember o) {
        return super.compare(o)
                .compareFalseFirst(visible, o.visible);
    }

    @Override
    public int compareTo(AnnotationMember o) {
        return compare(o).result();
    }
}