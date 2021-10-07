package com.tyron.completion.rewrite;

import com.tyron.completion.CompilerProvider;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public class RewriteNotSupported implements Rewrite {
    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Collections.emptyMap();
    }
}
