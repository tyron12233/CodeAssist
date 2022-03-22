package com.tyron.builder.api.internal.typeconversion;

import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

/**
 * A converter from notations of type {@link N} to results of type {@link T}.
 *
 * <p>This interface represents an SPI used to implement notation parsers, not the API to use to perform the conversions. Use {@link NotationParser} instead for this.
 */
public interface NotationConverter<N, T> {
    /**
     * Attempt to convert the given notation.
     *
     * @throws TypeConversionException when the notation is recognized but cannot be converted for some reason.
     */
    void convert(N notation, NotationConvertResult<? super T> result) throws TypeConversionException;

    /**
     * Describes the formats that this converter accepts.
     */
    void describe(DiagnosticsVisitor visitor);
}