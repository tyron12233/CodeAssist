package com.tyron.builder.api.internal.typeconversion;

import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Flattens or collectionizes input and passes the input notations to the delegates. Returns a set.
 */
public class FlatteningNotationParser<N, T> implements NotationParser<N, Set<T>> {

    private final NotationParser<N, T> delegate;

    public FlatteningNotationParser(NotationParser<N, T> delegate) {
        assert delegate != null : "delegate cannot be null";
        this.delegate = delegate;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        delegate.describe(visitor);
        visitor.candidate("Collections or arrays of any other supported format. Nested collections/arrays will be flattened.");
    }

    @Override
    public Set<T> parseNotation(N notation) {
        Collection<N> notations;
        if (notation == null) {
            notations = Collections.emptyList();
        } else {
            notations = Collections.singletonList(notation);
        }
        if (notations.isEmpty()) {
            return Collections.emptySet();
        }
        if (notations.size() == 1) {
            return Collections.singleton(delegate.parseNotation(notations.iterator().next()));
        }
        Set<T> out = new LinkedHashSet<T>();
        for (N n : notations) {
            out.add(delegate.parseNotation(n));
        }
        return out;
    }
}