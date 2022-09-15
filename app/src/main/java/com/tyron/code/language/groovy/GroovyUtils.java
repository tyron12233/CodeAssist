package com.tyron.code.language.groovy;

import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class GroovyUtils {

    public static Range toRange(SyntaxException exp) {
        // LSP Range start from 0, while groovy classes start from 1
        return new Range(new Position(exp.getStartLine() - 1, exp.getStartColumn() - 1),
                new Position(exp.getEndLine() - 1, exp.getEndColumn() - 1));
    }

    public static Range toRange(Expression expression) {
        // LSP Range start from 0, while groovy expressions start from 1
        return new Range(new Position(expression.getLineNumber() - 1, expression.getColumnNumber() - 1),
                new Position(expression.getLastLineNumber() - 1, expression.getLastColumnNumber() - 1));
    }

    public static Range toRange(Statement statement) {
        // LSP Range start from 0, while groovy expressions start from 1
        return new Range(new Position(statement.getLineNumber() - 1, statement.getColumnNumber() - 1),
                new Position(statement.getLastLineNumber() - 1, statement.getLastColumnNumber() - 1));
    }

    public static Range toDependencyRange(Expression expression) {
        // For dependency, the string includes open/close quotes should be excluded
        return new Range(new Position(expression.getLineNumber() - 1, expression.getColumnNumber()),
                new Position(expression.getLastLineNumber() - 1, expression.getLastColumnNumber() - 2));
    }
}
