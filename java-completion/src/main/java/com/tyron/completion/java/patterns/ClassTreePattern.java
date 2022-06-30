package com.tyron.completion.java.patterns;

import static com.tyron.completion.java.util.TreeUtil.*;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.string;

import androidx.annotation.NonNull;

import com.tyron.completion.java.patterns.elements.JavacElementPattern;
import com.tyron.completion.java.patterns.elements.JavacElementPatternCondition;
import com.tyron.completion.java.util.TreeUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.patterns.StringPattern;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.Elements;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

public class ClassTreePattern extends JavacTreeMemberPattern<ClassTree, ClassTreePattern>  {

    protected ClassTreePattern(@NonNull InitialPatternCondition<ClassTree> condition) {
        super(condition);
    }

    protected ClassTreePattern(Class<ClassTree> aClass) {
        super(aClass);
    }

    public ClassTreePattern inheritorOf(boolean strict, final ClassTreePattern pattern) {
        return with(new PatternCondition<ClassTree>("inheritorOf") {
            @Override
            public boolean accepts(@NotNull ClassTree t, ProcessingContext context) {
                return false;
            }
        });
    }

    private static boolean isInheritor(ClassTree classTree, ElementPattern pattern, final ProcessingContext matchingContext, boolean checkThisClass) {
        if (classTree == null) return false;
        if (checkThisClass && pattern.accepts(classTree, matchingContext)) return true;
        return false;
    }

    public ClassTreePattern isInterface() {
        return with(new PatternCondition<ClassTree>("isInterface") {
            @Override
            public boolean accepts(@NotNull ClassTree classTree,
                                   ProcessingContext processingContext) {
                return classTree.getKind() == Tree.Kind.INTERFACE;
            }
        });
    }

    public ClassTreePattern withField(final boolean checkDeep, final ElementPattern memberPattern) {
        return with(new JavacElementPatternCondition<ClassTree>("withField") {

            @Override
            public boolean accepts(@NotNull ClassTree classTree,
                                   ProcessingContext context) {
                for (Tree member : classTree.getMembers()) {
                    if (member.getKind() != Tree.Kind.VARIABLE) {
                        continue;
                    }

                    if (memberPattern.accepts(member, context)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean accepts(@NonNull Element element, ProcessingContext processingContext) {
                return false;
            }
        });
    }

    public ClassTreePattern withMethod(final boolean checkDeep, final ElementPattern<? extends Tree> memberPattern) {
        return with(new PatternCondition<ClassTree>("withMethod") {
            @Override
            public boolean accepts(@NotNull ClassTree classTree,
                                   ProcessingContext context) {

                if (checkDeep) {
                    Trees trees = (Trees) context.get("trees");
                    if (trees == null) {
                        return false;
                    }
                    Elements elements = (Elements) context.get("elements");
                    if (elements == null) {
                        return false;
                    }
                    CompilationUnitTree root = (CompilationUnitTree) context.get("root");
                    if (root == null) {
                        return false;
                    }

                    TreePath path = trees.getPath(root, classTree);
                    for (MethodTree methodTree : getAllMethods(trees, elements, path)) {
                        if (memberPattern.accepts(methodTree, context)) {
                            return true;
                        }
                    }
                } else {
                    for (MethodTree method : getMethods(classTree)) {
                        if (memberPattern.accepts(method, context)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        });
    }

    public ClassTreePattern withQualifiedName(@NonNull final String qname) {
        return with(new ClassTreeNamePatternCondition(string().equalTo(qname)));
    }

}
