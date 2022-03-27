package com.tyron.builder.api.internal.typeconversion;


import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;

/**
 * A parser from notations of type {@link N} to a result of type {@link T}. This interface is used by clients to perform the parsing. To implement a parser, you should use {@link NotationConverter}
 * instead.
 */
public interface NotationParser<N, T> {
    /**
     * @throws UnsupportedNotationException When the supplied notation is not handled by this parser.
     * @throws TypeConversionException When the supplied notation cannot be converted to the target type.
     */
    T parseNotation(N notation) throws TypeConversionException;

    /**
     * Describes the formats and values that the parser accepts.
     */
    void describe(DiagnosticsVisitor visitor);
}