package com.tyron.completion.java.rewrite;

import com.tyron.completion.java.CompilerProvider;
import com.tyron.completion.model.TextEdit;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public interface Rewrite {

    /** Perform a rewrite across the entire codebase. */
    Map<Path, TextEdit[]> rewrite(CompilerProvider compiler);
    /** CANCELLED signals that the rewrite couldn't be completed. */
    Map<Path, TextEdit[]> CANCELLED = Collections.emptyMap();

    Rewrite NOT_SUPPORTED = new RewriteNotSupported();
}
