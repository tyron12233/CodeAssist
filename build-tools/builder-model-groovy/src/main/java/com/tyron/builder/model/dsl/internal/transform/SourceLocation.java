package com.tyron.builder.model.dsl.internal.transform;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.core.rule.describe.SimpleModelRuleDescriptor;

import javax.annotation.Nullable;
import java.net.URI;

public class SourceLocation {
    private final @Nullable URI uri;
    private final String scriptSourceDescription;
    private final String expression;
    private final int lineNumber;
    private final int columnNumber;

    public SourceLocation(@Nullable URI uri, String scriptSourceDescription, String expression, int lineNumber, int columnNumber) {
        this.uri = uri;
        this.scriptSourceDescription = scriptSourceDescription;
        this.expression = expression;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    /**
     * Called from generated code. See {@link RuleVisitor#visitGeneratedClosure(org.codehaus.groovy.ast.ClassNode)}
     */
    @SuppressWarnings("unused")
    public SourceLocation(@Nullable String uri, String scriptSourceDescription, String expression, int lineNumber, int columnNumber) {
        this(uri == null ? null : URI.create(uri), scriptSourceDescription, expression, lineNumber, columnNumber);
    }

    public String getExpression() {
        return expression;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    @Nullable
    public URI getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return scriptSourceDescription + " line " + lineNumber + ", column " + columnNumber;
    }

    public ModelRuleDescriptor asDescriptor() {
        return new SimpleModelRuleDescriptor(expression + " @ " + toString());
//        throw new UnsupportedOperationException();
    }
}
