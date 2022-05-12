package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

public class ScriptBlock {
    private final String name;
    private final MethodCallExpression methodCall;
    private final ClosureExpression closureExpression;

    public ScriptBlock(String name, MethodCallExpression methodCall, ClosureExpression closureExpression) {
        this.name = name;
        this.methodCall = methodCall;
        this.closureExpression = closureExpression;
    }

    public String getName() {
        return name;
    }

    public MethodCallExpression getMethodCall() {
        return methodCall;
    }

    public ClosureExpression getClosureExpression() {
        return closureExpression;
    }
}
