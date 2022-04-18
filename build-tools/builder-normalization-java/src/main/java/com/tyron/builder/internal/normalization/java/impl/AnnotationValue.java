package com.tyron.builder.internal.normalization.java.impl;

public abstract class AnnotationValue<V> extends Member implements Comparable<AnnotationValue<?>> {

    private final V value;

    public AnnotationValue(String name, V value) {
        super(name);
        this.value = value;
    }

    public V getValue() {
        return value;
    }

    @Override
    public int compareTo(AnnotationValue<?> o) {
        return super.compare(o).result();
    }
}