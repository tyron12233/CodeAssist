package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import java.util.List;

public class ImperativeStatementDetectingTransformer extends AbstractScriptTransformer {
    private boolean imperativeStatementDetected;

    @Override
    public void register(CompilationUnit compilationUnit) {
        super.register(compilationUnit);
    }

    @Override
    protected int getPhase() {
        return Phases.CANONICALIZATION;
    }

    public boolean isImperativeStatementDetected() {
        return imperativeStatementDetected;
    }

    @Override
    public void call(SourceUnit source) throws CompilationFailedException {
        BlockStatement statementBlock = source.getAST().getStatementBlock();
        List<Statement> statements = statementBlock.getStatements();
        for (Statement statement : statements) {
            if (!AstUtils.mayHaveAnEffect(statement)) {
                continue;
            }
            ScriptBlock scriptBlock = AstUtils.detectScriptBlock(statement);
//            if (scriptBlock != null && scriptBlock.getName().equals(ModelBlockTransformer.MODEL)) {
//                continue;
//            }
            imperativeStatementDetected = true;
            break;
        }
    }
}
