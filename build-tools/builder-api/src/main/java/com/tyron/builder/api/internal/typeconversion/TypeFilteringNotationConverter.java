package com.tyron.builder.api.internal.typeconversion;


import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

class TypeFilteringNotationConverter<N, S, T> implements NotationConverter<N, T> {
    private final Class<S> type;
    private final NotationConverter<? super S, ? extends T> delegate;

    public TypeFilteringNotationConverter(Class<S> type, NotationConverter<? super S, ? extends T> delegate) {
        this.type = type;
        this.delegate = delegate;
    }

    @Override
    public void convert(N notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        if (type.isInstance(notation)) {
            delegate.convert(type.cast(notation), result);
        }
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        delegate.describe(visitor);
    }
}