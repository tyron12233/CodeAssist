package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import java.util.ListIterator;
import java.util.function.Predicate;

public class FilteringScriptTransformer extends AbstractScriptTransformer {

    private final Predicate<? super Statement> spec;

    public FilteringScriptTransformer(Predicate<? super Statement> spec) {
        this.spec = spec;
    }

    @Override
    protected int getPhase() {
        return Phases.CONVERSION;
    }

    @Override
    public void call(SourceUnit source) throws CompilationFailedException {
        source.getAST().getStatementBlock().getStatements().removeIf(spec);
    }
}
