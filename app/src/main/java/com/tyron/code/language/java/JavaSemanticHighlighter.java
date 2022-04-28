package com.tyron.code.language.java;

import androidx.annotation.NonNull;

import com.google.common.collect.Ordering;
import com.tyron.code.analyzer.semantic.SemanticToken;
import com.tyron.code.analyzer.semantic.TokenType;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class JavaSemanticHighlighter extends TreePathScanner<Void, Boolean> {

    private static final Ordering<SemanticToken> INCREASING =
            Ordering.from(Comparator.comparingInt(SemanticToken::getOffset));


    private JCTree.JCCompilationUnit cu;
    private SourcePositions pos;
    private final Trees trees;
    private final Elements elements;
    private List<SemanticToken> tokens;

    public JavaSemanticHighlighter(JavacTask task) {
        this.trees = Trees.instance(task);
        this.pos = trees.getSourcePositions();
        this.elements = task.getElements();
        this.tokens = new ArrayList<>();
    }


    private enum TokenModifier {

        ABSTRACT("abstract"),
        STATIC("static"),
        FINAL("readOnly"),
        DECLARATION("declaration"),

        PUBLIC("public"),
        PRIVATE("private"),
        PROTECTED("protected"),
        NATIVE("native"),
        GENERIC("generic"),
        TYPE_ARGUMENT("typeArgument"),
        IMPORT_DECLARATION("importDeclaration"),
        CONSTRUCTOR("constructor");

        private final String genericName;

        /**
         * The bitmask for this semantic token modifier.
         * Use bitwise OR to combine with other token modifiers.
         */
        public final int bitmask = 1 << ordinal();

        /**
         * The inverse bitmask for this semantic token modifier.
         * Use bitwise AND to remove from other token modifiers.
         */
        public final int inverseBitmask = ~bitmask;

        TokenModifier(String genericName) {
            this.genericName = genericName;
        }

        @NonNull
        @Override
        public String toString() {
            return genericName;
        }

        /**
         * Returns the bitwise OR of all the semantic token modifiers that apply
         * based on the binding's {@link Modifier}s and wheter or not it is deprecated.
         *
         * @param binding A binding.
         * @return The bitwise OR of the applicable modifiers for the binding.
         */
        public static int checkJavaModifiers(Element binding) {
            if (binding == null) {
                return 0;
            }

            int modifiers = 0;
            Set<Modifier> bindingModifiers = binding.getModifiers();
            if (bindingModifiers.contains(Modifier.PUBLIC)) {
                modifiers |= PUBLIC.bitmask;
            }
            if (bindingModifiers.contains(Modifier.PRIVATE)) {
                modifiers |= PRIVATE.bitmask;
            }
            if (bindingModifiers.contains(Modifier.PROTECTED)) {
                modifiers |= PROTECTED.bitmask;
            }
            if (bindingModifiers.contains(Modifier.ABSTRACT)) {
                modifiers |= ABSTRACT.bitmask;
            }
            if (bindingModifiers.contains(Modifier.STATIC)) {
                modifiers |= STATIC.bitmask;
            }
            if (bindingModifiers.contains(Modifier.FINAL)) {
                modifiers |= FINAL.bitmask;
            }
            if (bindingModifiers.contains(Modifier.NATIVE)) {
                modifiers |= NATIVE.bitmask;
            }
//            if (binding.isDeprecated()) {
//                modifiers |= DEPRECATED.bitmask;
//            }
            return modifiers;
        }
    }



    public List<SemanticToken> getTokens() {
        return INCREASING.immutableSortedCopy(tokens);
    }

    private void addToken(int offset, int length, TokenType tokenType, int modifiers) {
        tokens.add(new SemanticToken(offset, length, tokenType, modifiers));
    }

    private void addToken(JCTree node, TokenType tokenType, int modifiers) {
        addToken(node.getStartPosition(), node.getStartPosition(), tokenType, modifiers);
    }


    @Override
    public Void visitCompilationUnit(CompilationUnitTree t, Boolean b) {
        cu = (JCTree.JCCompilationUnit) t;
        return super.visitCompilationUnit(t, b);
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, Boolean b) {
        JCTree.JCIdent identifier = ((JCTree.JCIdent) t);

        Tree parent = getCurrentPath().getParentPath().getLeaf();
        Tree.Kind parentKind = parent.getKind();

        switch (parentKind) {
            case ANNOTATION:
                addAnnotation(identifier);
                break;
            case VARIABLE:
                addVariable(identifier);
                break;
            case MEMBER_SELECT:
                addMemberSelect(identifier);
                break;
            default:
                addIdentifier(identifier);
        }
        return super.visitIdentifier(t, b);
    }

    private void addMemberSelect(JCTree.JCIdent identifier) {
        Element element = trees.getElement(getCurrentPath());
        TokenType applicableType = JavaTokenTypes.getApplicableType(element);
        if (applicableType == null) {
            return;
        }

        int start = (int) pos.getStartPosition(cu, identifier);
        int end = (int) pos.getEndPosition(cu, identifier);
        addToken(start, end - start, applicableType, 0);
    }

    private void addVariable(JCTree.JCIdent identifier) {
        int start = identifier.getStartPosition();
        int end = start + identifier.getName().length();

        Element element = trees.getElement(getCurrentPath());
        addToken(start, end - start, JavaTokenTypes.CLASS, 0);
    }
    private void addIdentifier(JCTree.JCIdent identifier) {
        if (identifier == null) {
            return;
        }
        int start = identifier.getStartPosition();
        int end = start + identifier.getName().length();


    }
    private void addAnnotation(JCTree.JCIdent identifier) {
        TokenType tokenType = TokenType.UNKNOWN;

        Element element = trees.getElement(getCurrentPath());
        if (element != null) {
            TypeMirror type = element.asType();
            if (type.getKind() != TypeKind.ERROR) {
                tokenType = JavaTokenTypes.ANNOTATION;
            }
        }

        int start = identifier.getStartPosition();
        int end = (int) pos.getEndPosition(cu, identifier);
        addToken(start, end - start, tokenType, 0);
    }

    @Override
    public Void visitClass(ClassTree t, Boolean b) {
        Element element = trees.getElement(getCurrentPath());
        if (element == null) {
            return super.visitClass(t, b);
        }

        int start = (int) pos.getStartPosition(cu, t);

        String contents = getContents();
        start = contents.indexOf(element.getSimpleName().toString(), start);
        if (start == -1) {
            return super.visitClass(t, b);
        }
        int end = start + element.getSimpleName().length();

        TokenType applicableType = JavaTokenTypes.getApplicableType(element);
        if (applicableType != null) {
            addToken(start, end - start, applicableType, TokenModifier.checkJavaModifiers(element));
        }
        return super.visitClass(t, b);
    }

    @Override
    public Void visitMethod(MethodTree methodTree, Boolean aBoolean) {
        Element element = trees.getElement(getCurrentPath());
        if (element == null) {
            return super.visitMethod(methodTree, aBoolean);
        }
        
        boolean isConstructor = TreeInfo.isConstructor((JCTree) methodTree);
        String name;
        if (isConstructor) {
            name = element.getEnclosingElement().getSimpleName().toString();
        } else {
            name = element.getSimpleName().toString();
        }
        
        String contents = getContents();
        int start = (int) pos.getStartPosition(cu, methodTree);
        start = contents.indexOf(name, start);
        int end = start + name.length();
       
        long realEnd = pos.getEndPosition(cu, methodTree);
        if (realEnd != -1) {
            TokenType type;
            if (isConstructor) {
                type = JavaTokenTypes.CONSTRUCTOR;
            } else {
                type = JavaTokenTypes.METHOD_DECLARATION;
            }
            addToken(start, end - start, type, 0);
        }
        return super.visitMethod(methodTree, aBoolean);
    }

    @Override
    public Void visitVariable(VariableTree t, Boolean b) {
        JCTree tree = ((JCTree.JCVariableDecl) t);
        int start = tree.getPreferredPosition();
        int end = start + t.getName().length();
        Element element = trees.getElement(getCurrentPath());
        TokenType applicableType = JavaTokenTypes.getApplicableType(element);
        if (applicableType != null) {
            if (element.getModifiers().contains(Modifier.FINAL)) {
                addToken(start, end - start, JavaTokenTypes.CONSTANT, TokenModifier.checkJavaModifiers(element));
            } else {
                addToken(start, end - start, applicableType, TokenModifier.checkJavaModifiers(element));
            }
        }
        return super.visitVariable(t, b);
    }

    private void addSuper() {

    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree t, Boolean b) {
        JCTree.JCMethodInvocation method = ((JCTree.JCMethodInvocation) t);
        Element element = trees.getElement(getCurrentPath());
        int end = method.getPreferredPosition();

        int realEndPosition = (int) pos.getEndPosition(cu, t);
        if (end == -1 || element == null || realEndPosition == -1) {
            return super.visitMethodInvocation(t, b);
        }
        int start = end - element.getSimpleName().length();
        TokenType type = JavaTokenTypes.METHOD_CALL;
        if (element.getKind() == ElementKind.CONSTRUCTOR) {
            start = method.getStartPosition();
            end = start + "super".length();
            type = JavaTokenTypes.CONSTRUCTOR;
        }
        addToken(start, end - start, type, TokenModifier.checkJavaModifiers(element));
        return super.visitMethodInvocation(t, b);
    }

    @Override
    public Void visitErroneous(ErroneousTree t, Boolean b) {
        List<? extends Tree> errorTrees = t.getErrorTrees();
        if (errorTrees != null) {
            for (Tree errorTree : errorTrees) {
                scan(errorTree, b);
            }
        }
        return null;
    }

    private String getContents() {
        try {
            return String.valueOf(cu.getSourceFile().getCharContent(false));
        } catch (IOException e) {
            return "";
        }
    }
}
