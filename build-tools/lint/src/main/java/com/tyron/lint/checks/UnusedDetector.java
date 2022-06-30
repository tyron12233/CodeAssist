package com.tyron.lint.checks;

import com.tyron.lint.api.Detector;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class UnusedDetector extends Detector implements Detector.JavaScanner {

    @Override
    public List<Class<? extends Tree>> getApplicableTypes() {
        return Collections.singletonList(VariableTree.class);
    }

    @Override
    public JavaVoidVisitor getVisitor(JavaContext context) {
        return new UnusedScanner(context);
    }

    private static class UnusedScanner extends JavaVoidVisitor {

        private TreePath path;

        private void scanPath(TreePath path) {
            TreePath prev = this.path;
            this.path = path;
            try {
                path.getLeaf().accept(this, null);
            } finally {
                this.path = prev; // So we can call scan(path, _) recursively
            }
        }

        @Override
        public Void scan(Tree tree, Void p) {
            if (tree == null) return null;

            TreePath prev = path;
            path = new TreePath(path, tree);
            try {
                return tree.accept(this, p);
            } finally {
                path = prev;
            }
        }

        private final Trees trees;
        private final Map<Element, TreePath> privateDeclarations = new HashMap<>();
        private final Map<Element, TreePath> localVariables = new HashMap<>();
        private final Set<Element> used = new HashSet<>();

        public UnusedScanner(JavaContext context) {
            this.trees = Trees.instance(context.getCompileTask().task);
        }

        private Set<Element> notUsed() {
            Set<Element> unused = new HashSet<>();
            unused.addAll(privateDeclarations.keySet());
            unused.addAll(localVariables.keySet());
            unused.removeAll(used);
            // Remove if there are any null elements somehow ended up being added
            // during async work which calls `lint`
            unused.removeIf(Objects::isNull);
            // Remove if <error > field was injected while forming the AST
            unused.removeIf(i -> i.toString().equals("<error>"));
            return unused;
        }

        private void foundPrivateDeclaration() {
            privateDeclarations.put(trees.getElement(path), path);
        }

        private void foundLocalVariable() {
            localVariables.put(trees.getElement(path), path);
        }

        private void foundReference() {
            Element toEl = trees.getElement(path);
            if (toEl == null) {
                return;
            }
            if (toEl.asType().getKind() == TypeKind.ERROR) {
                foundPseudoReference(toEl);
                return;
            }
            sweep(toEl);
        }

        private void foundPseudoReference(Element toEl) {
            Element parent = toEl.getEnclosingElement();
            if (!(parent instanceof TypeElement)) {
                return;
            }
            Name memberName = toEl.getSimpleName();
            TypeElement type = (TypeElement) parent;
            for (Element member : type.getEnclosedElements()) {
                if (member.getSimpleName().contentEquals(memberName)) {
                    sweep(member);
                }
            }
        }

        private void sweep(Element toEl) {
            boolean firstUse = used.add(toEl);
            boolean notScanned = firstUse && privateDeclarations.containsKey(toEl);
            if (notScanned) {
                scanPath(privateDeclarations.get(toEl));
            }
        }

        private boolean isReachable(TreePath path) {
            // Check if t is reachable because it's public
            Tree t = path.getLeaf();
            if (t instanceof VariableTree) {
                VariableTree v = (VariableTree) t;
                boolean isPrivate = v.getModifiers().getFlags().contains(Modifier.PRIVATE);
                if (!isPrivate || isLocalVariable(path)) {
                    return true;
                }
            }
            if (t instanceof MethodTree) {
                MethodTree m = (MethodTree) t;
                boolean isPrivate = m.getModifiers().getFlags().contains(Modifier.PRIVATE);
                boolean isEmptyConstructor = m.getParameters().isEmpty() && m.getReturnType() == null;
                if (!isPrivate || isEmptyConstructor) {
                    return true;
                }
            }
            if (t instanceof ClassTree) {
                ClassTree c = (ClassTree) t;
                boolean isPrivate = c.getModifiers().getFlags().contains(Modifier.PRIVATE);
                if (!isPrivate) {
                    return true;
                }
            }
            // Check if t has been referenced by a reachable element
            Element el = trees.getElement(path);
            return used.contains(el);
        }

        private boolean isLocalVariable(TreePath path) {
            Tree.Kind kind = path.getLeaf().getKind();
            if (kind != Tree.Kind.VARIABLE) {
                return false;
            }
            Tree.Kind parent = path.getParentPath().getLeaf().getKind();
            if (parent == Tree.Kind.CLASS || parent == Tree.Kind.INTERFACE) {
                return false;
            }
            if (parent == Tree.Kind.METHOD) {
                MethodTree method = (MethodTree) path.getParentPath().getLeaf();
                if (method.getBody() == null) {
                    return false;
                }
            }
            return true;
        }

        private CompilationUnitTree root;
        @Override
        public Void visitCompilationUnit(CompilationUnitTree compilationUnitTree, Void unused) {
            root = compilationUnitTree;
            return super.visitCompilationUnit(compilationUnitTree, unused);
        }

        @Override
        public Void visitVariable(VariableTree t, Void __) {
            TreePath path = trees.getPath(root, t);
            if (isLocalVariable(path)) {
                foundLocalVariable();
                super.visitVariable(t, null);
            } else if (isReachable(path)) {
                super.visitVariable(t, null);
            } else {
                foundPrivateDeclaration();
            }
            return null;
        }
    }
}
