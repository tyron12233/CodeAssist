package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.internal.typeconversion.NotationConverter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.internal.typeconversion.TypeConversionException;

import java.io.File;
import java.util.concurrent.Callable;

import static com.tyron.builder.util.internal.GUtil.uncheckedCall;

public class PathNotationConverter implements NotationConverter<Object, String> {

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("String or CharSequence instances").example("\"some/path\"");
        visitor.candidate("Boolean values").example("true").example("Boolean.TRUE");
        visitor.candidate("Number values").example("42").example("3.14");
        visitor.candidate("A File instance");
        visitor.candidate("A Closure that returns any supported value.");
        visitor.candidate("A Callable that returns any supported value.");
        visitor.candidate("A Provider that provides any supported value.");
    }

    public static NotationParser<Object, String> parser() {
        return NotationParserBuilder
                .toType(String.class)
                .noImplicitConverters()
                .allowNullInput()
                .converter(new PathNotationConverter())
                .toComposite();
    }

    @Override
    public void convert(Object notation, NotationConvertResult<? super String> result) throws TypeConversionException {
        if (notation == null) {
            result.converted(null);
        } else if (notation instanceof CharSequence
                || notation instanceof File
                || notation instanceof Number
                || notation instanceof Boolean) {
            result.converted(notation.toString());
        } else if (notation instanceof Callable) {
            final Callable<?> callableNotation = (Callable<?>) notation;
            final Object called = uncheckedCall(callableNotation);
            convert(called, result);
        } else if (notation instanceof Provider) {
            final Provider<?> providerNotation = (Provider<?>) notation;
            final Object called = providerNotation.get();
            convert(called, result);
        }
    }
}
