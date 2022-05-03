package com.tyron.builder.groovy.scripts;

import org.codehaus.groovy.control.CompilationUnit;

public interface Transformer {

    void register(CompilationUnit compilationUnit);
}
