package com.tyron.completion.java.rewrite;

import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public class RewriteNotSupported implements JavaRewrite {
    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Collections.emptyMap();
    }
}
