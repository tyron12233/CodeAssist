package com.tyron.completion.java.parse;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.tyron.completion.java.util.ErrorAwareTreeScanner;

import java.util.HashSet;
import java.util.Set;

/**
 * Partial reparse helper visitor.
 * Finds anonymous and local classes in given method tree.
 * @author Tomas Zezula
 */
class FindAnonymousVisitor extends ErrorAwareTreeScanner<Void,Void> {

    private static enum Mode {COLLECT, CHECK};

    int noInner;
    boolean hasLocalClass;
    final Set<Tree> docOwners = new HashSet<>();
    private Mode mode = Mode.COLLECT;            
    
    public final void reset () {
        this.noInner = 0;
        this.hasLocalClass = false;
        this.mode = Mode.CHECK;
    }

    @Override
    public Void visitClass(ClassTree node, Void p) {
        if (node.getSimpleName().length() != 0) {
            hasLocalClass = true;
        }
        noInner++;
        handleDoc(node);
        return super.visitClass(node, p);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        handleDoc(node);
        return super.visitMethod(node, p);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        handleDoc(node);
        return super.visitVariable(node, p);
    }

    private void handleDoc (final Tree tree) {
        if (mode == Mode.COLLECT) {
            docOwners.add(tree);
        }
    }

}
