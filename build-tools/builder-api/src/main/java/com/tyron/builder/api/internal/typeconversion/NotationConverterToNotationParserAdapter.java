package com.tyron.builder.api.internal.typeconversion;


import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

public class NotationConverterToNotationParserAdapter<N, T> implements NotationParser<N, T> {
    private final NotationConverter<? super N, ? extends T> converter;

    public NotationConverterToNotationParserAdapter(NotationConverter<? super N, ? extends T> converter) {
        this.converter = converter;
    }

    @Override
    public T parseNotation(N notation) throws TypeConversionException {
        ResultImpl<T> result = new ResultImpl<>();
        converter.convert(notation, result);
        if (!result.hasResult) {
            throw new UnsupportedNotationException(notation);
        }
        return result.result;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        converter.describe(visitor);
    }

    private static class ResultImpl<T> implements NotationConvertResult<T> {
        private boolean hasResult;
        private T result;

        @Override
        public boolean hasResult() {
            return hasResult;
        }

        @Override
        public void converted(T result) {
            hasResult = true;
            this.result = result;
        }
    }
}
