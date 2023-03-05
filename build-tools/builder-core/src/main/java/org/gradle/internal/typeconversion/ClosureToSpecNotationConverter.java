package org.gradle.internal.typeconversion;

import groovy.lang.Closure;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.exceptions.DiagnosticsVisitor;

public class ClosureToSpecNotationConverter<T> implements NotationConverter<Closure, Spec<T>> {
    private final Class<T> type;

    public ClosureToSpecNotationConverter(Class<T> type) {
        this.type = type;
    }

    @Override
    public void convert(Closure notation, NotationConvertResult<? super Spec<T>> result) throws TypeConversionException {
        Spec<T> spec = Specs.convertClosureToSpec(notation);
        result.converted(spec);
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate(String.format("Closure that returns boolean and takes a single %s as a parameter.", type.getSimpleName()));
    }
}
