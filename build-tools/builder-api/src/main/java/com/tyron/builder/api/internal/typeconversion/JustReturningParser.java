package com.tyron.builder.api.internal.typeconversion;

import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

public class JustReturningParser<N, T> implements NotationParser<N, T> {
    private final Class<? extends T> passThroughType;
    private final NotationParser<N, T> delegate;

    public JustReturningParser(Class<? extends T> passThroughType, NotationParser<N, T> delegate) {
        this.passThroughType = passThroughType;
        this.delegate = delegate;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate(String.format("Instances of %s.", passThroughType.getSimpleName()));
        delegate.describe(visitor);
    }

    @Override
    public T parseNotation(N notation) throws TypeConversionException {
        if (passThroughType.isInstance(notation)) {
            return passThroughType.cast(notation);
        } else {
            return delegate.parseNotation(notation);
        }
    }
}