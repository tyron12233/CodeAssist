package com.tyron.builder.internal.typeconversion;

import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;

public abstract class TypedNotationConverter<N, T> implements NotationConverter<Object, T> {

    private final Class<N> typeToken;

    public TypedNotationConverter(Class<N> typeToken) {
        assert typeToken != null : "typeToken cannot be null";
        this.typeToken = typeToken;
    }

    public TypedNotationConverter(TypeInfo<N> typeToken) {
        assert typeToken != null : "typeToken cannot be null";
        this.typeToken = typeToken.getTargetType();
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate(String.format("Instances of %s.", typeToken.getSimpleName()));
    }

    @Override
    public void convert(Object notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        if (typeToken.isInstance(notation)) {
            result.converted(parseType(typeToken.cast(notation)));
        }
    }

    abstract protected T parseType(N notation);
}
