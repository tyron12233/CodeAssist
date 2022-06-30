package com.tyron.builder.internal.typeconversion;

import com.tyron.builder.cache.internal.CrossBuildInMemoryCache;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;

/**
 * A {@link NotationConverter} that caches the result of conversion across build invocations.
 */
public class CrossBuildCachingNotationConverter<T> implements NotationConverter<Object, T> {
    private final CrossBuildInMemoryCache<Object, T> cache;
    private final NotationConverterToNotationParserAdapter<Object, T> delegate;

    public CrossBuildCachingNotationConverter(NotationConverter<Object, T> delegate, CrossBuildInMemoryCache<Object, T> cache) {
        this.cache = cache;
        this.delegate = new NotationConverterToNotationParserAdapter<>(delegate);
    }

    @Override
    public void convert(Object notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        T value = cache.get(notation, () -> delegate.parseNotation(notation));
        result.converted(value);
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        delegate.describe(visitor);
    }
}
