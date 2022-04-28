package com.tyron.completion.java.rewrite;

import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Map;

/**
 * Converts an anonymous class into a lambda expression
 */
public class ConvertToLambda implements JavaRewrite {

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return null;
    }
}
