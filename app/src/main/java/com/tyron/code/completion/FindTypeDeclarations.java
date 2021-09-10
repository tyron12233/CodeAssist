package com.tyron.code.completion;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FindTypeDeclarations extends TreeScanner<Void, List<String>> {
    private final List<CharSequence> qualifiedName = new ArrayList<>();

    @Override
    public Void visitCompilationUnit(CompilationUnitTree root, List<String> found) {
        String name = Objects.toString(root.getPackageName(), "");
        qualifiedName.add(name);
        return super.visitCompilationUnit(root, found);
    }

    @Override
    public Void visitClass(ClassTree type, List<String> found) {
        qualifiedName.add(type.getSimpleName());
        found.add(String.join(".", qualifiedName));
        super.visitClass(type, found);
        qualifiedName.remove(qualifiedName.size() - 1);
        return null;
    }
}