package org.gradle.api.internal.file.copy;

import groovy.lang.Closure;
import org.gradle.api.Transformer;

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
