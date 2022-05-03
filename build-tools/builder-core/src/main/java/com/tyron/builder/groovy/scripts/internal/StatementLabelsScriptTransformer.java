package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

public class StatementLabelsScriptTransformer extends AbstractScriptTransformer {
    @Override
    protected int getPhase() {
        return Phases.CANONICALIZATION;
    }

    @Override
    public void call(final SourceUnit source) throws CompilationFailedException {
        // currently we only look in script code; could extend this to build script classes
        AstUtils.visitScriptCode(source, new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            @Override
            protected void visitStatement(Statement statement) {
                if (statement.getStatementLabels() != null && !statement.getStatementLabels().isEmpty()) {
                    String message = String.format("Statement labels may not be used in build scripts.%nIn case you tried to configure a property named '%s', replace ':' with '=' or ' ', otherwise it will not have the desired effect.",
                            statement.getStatementLabels().get(0));
                    addError(message, statement);
                }
            }
        });
    }
}
