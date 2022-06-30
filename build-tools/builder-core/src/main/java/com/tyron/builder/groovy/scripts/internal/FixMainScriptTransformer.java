package com.tyron.builder.groovy.scripts.internal;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Fixes problem where main { } inside a closure is resolved as a call to static method main(). Does this by removing
 * the static method.
 */
public class FixMainScriptTransformer extends AbstractScriptTransformer {
    @Override
    protected int getPhase() {
        return Phases.CONVERSION;
    }

    @Override
    public void call(SourceUnit source) throws CompilationFailedException {
        ClassNode scriptClass = AstUtils.getScriptClass(source);
        if (scriptClass == null) {
            return;
        }
        for (MethodNode methodNode : scriptClass.getMethods()) {
            if (methodNode.getName().equals("main")) {
                AstUtils.removeMethod(scriptClass, methodNode);
                break;
            }
        }
    }
}
