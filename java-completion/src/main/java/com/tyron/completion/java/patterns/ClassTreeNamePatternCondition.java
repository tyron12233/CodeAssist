package com.tyron.completion.java.patterns;

import com.tyron.completion.java.patterns.elements.JavacElementPattern;
import com.tyron.completion.java.util.FindQualifiedName;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;

public class ClassTreeNamePatternCondition extends PatternCondition<ClassTree> implements JavacElementPattern {

    private final ElementPattern<String> namePattern;

    public ClassTreeNamePatternCondition(ElementPattern<String> pattern) {
        this("withQualifiedName", pattern);
    }
    public ClassTreeNamePatternCondition(@Nullable @NonNls String debugMethodName,
                                         ElementPattern<String> pattern) {
        super(debugMethodName);
        namePattern = pattern;
    }

    @Override
    public boolean accepts(@NotNull ClassTree t, ProcessingContext context) {
        Trees trees = (Trees) context.get("trees");
        if (trees == null) {
            return false;
        }
        CompilationUnitTree root = (CompilationUnitTree) context.get("root");
        if (root == null) {
            return false;
        }
        String name = new FindQualifiedName().scan(root, t);
        return namePattern.accepts(name, context);
    }

    public ElementPattern<String> getNamePattern() {
        return namePattern;
    }

    @Override
    public boolean accepts(Element element, ProcessingContext context) {
        if (element instanceof Symbol.ClassSymbol) {
            String name = ((Symbol.ClassSymbol) element).fullname.toString();
            return namePattern.accepts(name, context);
        } else if (element instanceof Symbol.MethodSymbol) {
            String s = ((Symbol.MethodSymbol) element).name.toString();
            return namePattern.accepts(s, context);
        }
        return false;
    }
}
