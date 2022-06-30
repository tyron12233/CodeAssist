package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.Transformer;

import org.codehaus.groovy.control.CompilationUnit;

@SuppressWarnings("deprecation")
public abstract class AbstractScriptTransformer extends CompilationUnit.SourceUnitOperation implements Transformer {
    @Override
    public void register(CompilationUnit compilationUnit) {
        compilationUnit.addPhaseOperation(this, getPhase());
    }

    protected abstract int getPhase();
}
