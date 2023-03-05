package com.tyron.completion.java.parse;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;

import java.io.IOException;

public interface PartialReparser {
    boolean reparseMethod(final CompilationInfoImpl ci,
                          final CharSequence contents,
                          final CompilationUnitTree compilationUnitTree,
                          final MethodTree orig,
                          final String newBody) throws IOException;
}