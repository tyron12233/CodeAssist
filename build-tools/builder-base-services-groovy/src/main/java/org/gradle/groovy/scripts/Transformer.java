package org.gradle.groovy.scripts;

import org.codehaus.groovy.control.CompilationUnit;

public interface Transformer {

    void register(CompilationUnit compilationUnit);
}
