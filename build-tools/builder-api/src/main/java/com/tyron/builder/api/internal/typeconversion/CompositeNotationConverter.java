package com.tyron.builder.api.internal.typeconversion;

import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

import java.util.List;


import java.util.List;

public class CompositeNotationConverter<N, T> implements NotationConverter<N, T> {
    private final List<NotationConverter<? super N, ? extends T>> converters;

    public CompositeNotationConverter(List<NotationConverter<? super N, ? extends T>> converters) {
        this.converters = converters;
    }

    @Override
    public void convert(N notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        for (int i = 0; !result.hasResult() && i < converters.size(); i++) {
            NotationConverter<? super N, ? extends T> converter = converters.get(i);
            converter.convert(notation, result);
        }
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        for (NotationConverter<? super N, ? extends T> converter : converters) {
            converter.describe(visitor);
        }
    }
}