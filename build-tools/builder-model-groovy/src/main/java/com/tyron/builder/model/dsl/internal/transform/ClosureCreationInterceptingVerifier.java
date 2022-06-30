package com.tyron.builder.model.dsl.internal.transform;

import com.tyron.builder.api.Action;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The verifier is the only thing in the Groovy compiler chain that gets to visit the classes generated from closure expressions. If we want to transform these classes, we have to do it with this
 * *hack*.
 */
@ThreadSafe
public class ClosureCreationInterceptingVerifier implements Action<ClassNode> {

    public static final Action<ClassNode> INSTANCE = new ClosureCreationInterceptingVerifier();

    @Override
    public void execute(ClassNode node) {
        if (node.implementsInterface(ClassHelper.GENERATED_CLOSURE_Type)) {
//            RulesVisitor.visitGeneratedClosure(node);
//            RuleVisitor.visitGeneratedClosure(node);
        }
    }
}
