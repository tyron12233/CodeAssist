package com.tyron.code.rewrite;

import com.tyron.code.completion.CompilerProvider;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Map;

/**
 * Converts an anonymous class into a lambda expression
 */
public class ConvertToLambda implements Rewrite {

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return null;
    }
}
