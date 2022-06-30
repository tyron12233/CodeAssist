package com.tyron.builder.internal.typeconversion;

public interface TypeConverter {
    /**
     * @param type The target type. Should be the boxed type for primitives.
     * @throws TypeConversionException On failure.
     * @throws UnsupportedNotationException On unsupported input.
     */
    Object convert(Object notation, Class<?> type, boolean primitive) throws TypeConversionException;
}
