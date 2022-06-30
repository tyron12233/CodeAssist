package com.tyron.completion.java.provider;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * TreeScanner that looks for occurrences of qualified names
 */
public class FindTypeDeclarationNamed extends TreeScanner<ClassTree, String> {
    private final List<CharSequence> qualifiedName = new ArrayList<>();

    @Override
    public ClassTree visitCompilationUnit(CompilationUnitTree t, String find) {
        String name = Objects.toString(t.getPackageName(), "");
        qualifiedName.add(name);
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public ClassTree visitClass(ClassTree t, String find) {
        qualifiedName.add(t.getSimpleName());
        if (String.join(".", qualifiedName).equals(find)) {
            return t;
        }
        ClassTree recurse = super.visitClass(t, find);
        qualifiedName.remove(qualifiedName.size() - 1);
        return recurse;
    }

    @Override
    public ClassTree reduce(ClassTree a, ClassTree b) {
        if (a != null) return a;
        return b;
    }
}