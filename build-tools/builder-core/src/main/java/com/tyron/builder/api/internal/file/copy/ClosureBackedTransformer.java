package com.tyron.builder.api.internal.file.copy;

import groovy.lang.Closure;
import com.tyron.builder.api.Transformer;

public class ClosureBackedTransformer implements Transformer<String, String> {
    private final Closure closure;

    public ClosureBackedTransformer(Closure closure) {
        this.closure = closure;
    }

    @Override
    public String transform(String s) {
        Object val = closure.call(s);
        return val == null ? null : val.toString();
    }
}
