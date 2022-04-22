package com.tyron.builder.internal.exceptions;


import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;

import java.util.SortedSet;
import java.util.TreeSet;

public class ValueCollectingDiagnosticsVisitor implements DiagnosticsVisitor {
    private final TreeSet<String> values = new TreeSet<String>();

    public SortedSet<String> getValues() {
        return values;
    }

    @Override
    public DiagnosticsVisitor candidate(String displayName) {
        return this;
    }

    @Override
    public DiagnosticsVisitor example(String example) {
        return this;
    }

    @Override
    public DiagnosticsVisitor values(Iterable<?> values) {
        for (Object value : values) {
            this.values.add(value.toString());
        }
        return this;
    }
}
