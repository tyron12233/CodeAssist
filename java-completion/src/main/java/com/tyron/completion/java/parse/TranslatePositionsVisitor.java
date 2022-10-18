package com.tyron.completion.java.parse;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.tyron.completion.java.util.ErrorAwareTreeScanner;

/**
 * Helper visitor for partial reparse.
 * Updates tree positions by the given delta.
 *
 * @author Tomas Zezula
 */
class TranslatePositionsVisitor extends ErrorAwareTreeScanner<Void, Void> {

    private final MethodTree changedMethod;
    private final EndPosTable endPos;
    private final int delta;
    boolean active;
    boolean inMethod;

    public TranslatePositionsVisitor(final MethodTree changedMethod,
                                     final EndPosTable endPos,
                                     final int delta) {
//        assert changedMethod != null;
        assert endPos != null;
        this.changedMethod = changedMethod;
        this.endPos = endPos;
        this.delta = delta;

        active = changedMethod == null;//hack
    }


    @Override
    public Void scan(Tree node, Void p) {
        if (active && node != null) {
            if (((JCTree) node).pos >= 0) {
                ((JCTree) node).pos += delta;
            }
        }
        Void result = super.scan(node, p);
        if (inMethod && node != null) {
            endPos.replaceTree((JCTree) node, null);//remove
        }
        if (active && node != null) {
            Integer pos = endPos.replaceTree((JCTree) node, null);//remove
            if (pos != null) {
                int newPos;
                if (pos < 0) {
                    newPos = pos;
                } else {
                    newPos = pos + delta;
                }
                endPos.storeEnd((JCTree) node, newPos);
            }
        }
        return result;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree node, Void p) {
        return scan(node.getTypeDecls(), p);
    }


    @Override
    public Void visitMethod(MethodTree node, Void p) {
        if (active || inMethod) {
            scan(node.getModifiers(), p);
            scan(node.getReturnType(), p);
            scan(node.getTypeParameters(), p);
            scan(node.getParameters(), p);
            scan(node.getThrows(), p);
        }
        if (node == changedMethod) {
            inMethod = true;
        }
        if (active || inMethod) {
            scan(node.getBody(), p);
        }
        if (inMethod) {
            active = inMethod;
            inMethod = false;
        }
        if (active || inMethod) {
            scan(node.getDefaultValue(), p);
        }
        return null;
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        JCVariableDecl varDecl = (JCVariableDecl) node;
        if (varDecl.sym != null && active && varDecl.sym.pos >= 0) {
            varDecl.sym.pos += delta;
        }
        return super.visitVariable(node, p);
    }


}