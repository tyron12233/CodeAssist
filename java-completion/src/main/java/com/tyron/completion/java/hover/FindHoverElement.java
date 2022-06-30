package com.tyron.completion.java.hover;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.Element;

/**
 * Class that searches where the current cursor is and returns the element
 * corresponding to it
 */
public class FindHoverElement extends TreePathScanner<Element, Long> {

    private final JavacTask task;
    private CompilationUnitTree root;

    public FindHoverElement(JavacTask task) {
        this.task = task;
    }

    @Override
    public Element visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public Element visitIdentifier(IdentifierTree t, Long find) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        long start = pos.getStartPosition(root, t);
        long end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public Element visitMemberSelect(MemberSelectTree t, Long find) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        long start = pos.getEndPosition(root, t.getExpression()) + 1;
        long end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitMemberSelect(t, find);
    }

    @Override
    public Element visitMemberReference(MemberReferenceTree t, Long find) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        long start = pos.getStartPosition(root, t.getQualifierExpression()) + 2;
        long end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitMemberReference(t, find);
    }

    @Override
    public Element reduce(Element a, Element b) {
        if (a != null) return a;
        else return b;
    }
}