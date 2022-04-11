package com.tyron.builder.api.internal.typeconversion;


import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

class CharSequenceNotationConverter<N, T> implements NotationConverter<N, T> {
    private final NotationConverter<? super String, ? extends T> delegate;

    public CharSequenceNotationConverter(NotationConverter<? super String, ? extends T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void convert(N notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        if (notation instanceof CharSequence) {
            CharSequence charSequence = (CharSequence) notation;
            delegate.convert(charSequence.toString(), result);
        }
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        delegate.describe(visitor);
    }
}