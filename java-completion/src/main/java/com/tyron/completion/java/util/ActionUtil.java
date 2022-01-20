package com.tyron.completion.java.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.rewrite.EditHelper;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.TypeParameterElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.doctree.ThrowsTree;
import org.openjdk.source.tree.AnnotationTree;
import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.EnhancedForLoopTree;
import org.openjdk.source.tree.ExpressionStatementTree;
import org.openjdk.source.tree.ForLoopTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.IfTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.ParameterizedTypeTree;
import org.openjdk.source.tree.ParenthesizedTree;
import org.openjdk.source.tree.PrimitiveTypeTree;
import org.openjdk.source.tree.ReturnTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.TryTree;
import org.openjdk.source.tree.WhileLoopTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TreePath;
import org.openjdk.tools.javac.tree.JCTree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ActionUtil {

    public static boolean canIntroduceLocalVariable(@NonNull TreePath path) {
        TreePath parent = path.getParentPath();

        if (path.getLeaf() instanceof MethodTree) {
            return false;
        }

        if (path.getLeaf() instanceof ParameterizedTypeTree) {
            return false;
        }

        if (path.getLeaf() instanceof AnnotationTree) {
            return false;
        }

        if (path.getLeaf() instanceof JCTree.JCVariableDecl) {
            return false;
        }

        if (path.getLeaf() instanceof MemberSelectTree) {
            return ActionUtil.canIntroduceLocalVariable(path.getParentPath());
        }

        if (path.getLeaf() instanceof PrimitiveTypeTree) {
            return ActionUtil.canIntroduceLocalVariable(path.getParentPath());
        }

        if (path.getLeaf() instanceof IdentifierTree) {
            return ActionUtil.canIntroduceLocalVariable(path.getParentPath());
        }

        if (path.getLeaf() instanceof MethodInvocationTree) {
            JCTree.JCMethodInvocation methodInvocation = (JCTree.JCMethodInvocation) path.getLeaf();
            // void return type
            if (isVoid(methodInvocation)) {
                return false;
            }

            TreePath decl = TreeUtil.findParentOfType(path, JCTree.JCVariableDecl.class);
            if (decl != null) {
                return false;
            }
        }

        if (path.getLeaf() instanceof BlockTree) {
            // method() { cursor }
            if (parent.getLeaf() instanceof MethodTree) {
                return false;
            }
        }

        if (parent == null) {
            return false;
        }

        TreePath grandParent = parent.getParentPath();
        if (parent.getLeaf() instanceof ParenthesizedTree) {
            if (grandParent.getLeaf() instanceof IfTree) {
                return false;
            }
            if (grandParent.getLeaf() instanceof WhileLoopTree) {
                return false;
            }
            if (grandParent.getLeaf() instanceof ForLoopTree) {
                return false;
            }
        }

        // can't introduce a local variable on a lambda expression
        // eg. run(() -> something());
        if (parent.getLeaf() instanceof JCTree.JCLambda) {
            return false;
        }

        if (parent.getLeaf() instanceof JCTree.JCBlock) {
            // run(() -> { something(); });
            if (grandParent.getLeaf() instanceof JCTree.JCLambda) {
                return true;
            }
        }

        // class Class { }
        if (path.getLeaf() instanceof ClassTree) {
            return false;
        }

        if (path.getLeaf() instanceof NewClassTree) {
            // run(new Runnable() { });
            if (parent.getLeaf() instanceof MethodInvocationTree) {
                return false;
            }
        }

        // throw ...
        if (parent.getLeaf() instanceof JCTree.JCThrow) {
            return false;
        }

        // return ...
        if (parent.getLeaf() instanceof ReturnTree) {
            return false;
        }
        return !(parent.getLeaf() instanceof ThrowsTree);
    }

    private static boolean isVoid(JCTree.JCMethodInvocation methodInvocation) {
        if (!methodInvocation.type.isPrimitive()) {
            return methodInvocation.type.isPrimitiveOrVoid();
        }
        return false;
    }

    public static TreePath findSurroundingPath(TreePath path) {
        TreePath parent = path.getParentPath();
        TreePath grandParent = parent.getParentPath();

        if (parent.getLeaf() instanceof JCTree.JCVariableDecl) {
            return parent;
        }
        // inside if parenthesis
        if (parent.getLeaf() instanceof ParenthesizedTree) {
            if (grandParent.getLeaf() instanceof IfTree)  {
                return grandParent;
            }
            if (grandParent.getLeaf() instanceof WhileLoopTree) {
                return grandParent;
            }
            if (grandParent.getLeaf() instanceof ForLoopTree) {
                return grandParent;
            }
            if (grandParent.getLeaf() instanceof EnhancedForLoopTree) {
                return grandParent;
            }
        }

        if (grandParent.getLeaf() instanceof BlockTree) {
            // try catch statement
            if (grandParent.getParentPath().getLeaf() instanceof TryTree) {
                return grandParent.getParentPath();
            }

            if (grandParent.getParentPath().getLeaf() instanceof JCTree.JCLambda) {
                return parent;
            }
        }

        if (parent.getLeaf() instanceof ExpressionStatementTree) {
            if (grandParent.getLeaf() instanceof ThrowsTree) {
                return null;
            }
            return parent;
        }
        return null;
    }

    public static TypeMirror getReturnType(JavacTask task, TreePath path, ExecutableElement element) {
        if (path.getLeaf() instanceof JCTree.JCNewClass) {
            JCTree.JCNewClass newClass = (JCTree.JCNewClass) path.getLeaf();
            return newClass.type;
        }
        return element.getReturnType();
    }

    /**
     * Used to check whether we need to add fully qualified names instead of importing it
     */
    public static boolean needsFqn(CompilationUnitTree root, String className) {
        return needsFqn(root.getImports().stream()
                .map(ImportTree::getQualifiedIdentifier)
                .map(Tree::toString)
                .collect(Collectors.toSet()), className);
    }

    public static boolean needsFqn(Set<String> imports, String className) {
        String name = getSimpleName(className);
        for (String fqn : imports) {
            if (fqn.equals(className)) {
                return false;
            }
            String simpleName = getSimpleName(fqn);
            if (simpleName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasImport(CompilationUnitTree root, String className) {
        if (className.endsWith("[]")) {
            className = className.substring(0, className.length() - 2);
        }
        if (className.contains("<")) {
            className = className.substring(0, className.indexOf('<'));
        }

        return hasImport(root.getImports().stream()
                .map(ImportTree::getQualifiedIdentifier)
                .map(Tree::toString)
                .collect(Collectors.toSet()), className);
    }

    public static boolean hasImport(Set<String> importDeclarations, String className) {

        String packageName = "";
        if (className.contains(".")) {
            packageName = className.substring(0, className.lastIndexOf("."));
        }
        if (packageName.equals("java.lang")) {
            return true;
        }

        if (needsFqn(importDeclarations, className)) {
            return true;
        }

        for (String name : importDeclarations) {
            if (name.equals(className)) {
                return true;
            }
            if (name.endsWith("*")) {
                String first = name.substring(0, name.lastIndexOf("."));
                String end = className.substring(0, className.lastIndexOf("."));
                if (first.equals(end)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String getSimpleName(TypeMirror typeMirror) {
        return EditHelper.printType(typeMirror, false).toString();
    }

    public static String getSimpleName(String className) {
        className = removeDiamond(className);

        int dot = className.lastIndexOf('.');
        if (dot == -1) {
            return className;
        }
        if (className.startsWith("? extends")) {
            return "? extends " + className.substring(dot + 1);
        }
        return className.substring(dot + 1);
    }

    public static String removeDiamond(String className) {
        if (className.contains("<")) {
            className = className.substring(0, className.indexOf('<'));
        }
        return className;
    }

    public static String removeArray(String className) {
        if (className.contains("[")) {
            className = className.substring(0, className.indexOf('['));
        }
        return className;
    }

    /**
     * @return null if type is an anonymous class
     */
    @Nullable
    public static String guessNameFromType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declared = (DeclaredType) type;
            Element element = declared.asElement();
            String name = element.getSimpleName().toString();
            // anonymous class, guess from class name
            if (name.length() == 0) {
                name = declared.toString();
                name = name.substring("<anonymous ".length(), name.length() - 1);
                name = ActionUtil.getSimpleName(name);
            }
            return "" + Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        return null;
    }

    public static String guessNameFromMethodName(String methodName) {
        if (methodName == null) {
            return null;
        }
        if (methodName.startsWith("get")) {
            methodName = methodName.substring("get".length());
        }
        if (methodName.isEmpty()) {
            return null;
        }
        if ("<init>".equals(methodName)) {
            return null;
        }
        if ("<clinit>".equals(methodName)) {
            return null;
        }
        return  Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
    }

    /**
     * Get all the possible fully qualified names that may be imported
     * @param type method to scan
     * @return Set of fully qualified names not including the diamond operator
     */
    public static Set<String> getTypesToImport(ExecutableType type) {
        Set<String> types = new HashSet<>();

        TypeMirror returnType = type.getReturnType();
        if (returnType != null) {
            if (returnType.getKind() != TypeKind.VOID && returnType.getKind() != TypeKind.TYPEVAR) {
                String fqn = getTypeToImport(returnType);
                if (fqn != null) {
                    types.add(fqn);
                }
            }
        }

        if (type.getThrownTypes() != null) {
            for (TypeMirror thrown : type.getThrownTypes()) {
                String fqn = getTypeToImport(thrown);
                if (fqn != null) {
                    types.add(fqn);
                }
            }
        }
        for (TypeMirror t : type.getParameterTypes()) {
            String fqn = getTypeToImport(t);
            if (fqn != null) {
                types.add(fqn);
            }
        }
        return types;
    }

    @Nullable
    private static String getTypeToImport(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return null;
        }

        if (type.getKind() == TypeKind.TYPEVAR) {
            return null;
        }

        String fqn = EditHelper.printType(type, true).toString();
        if (type.getKind() == TypeKind.ARRAY) {
            fqn = removeArray(fqn);
        }
        return removeDiamond(fqn);
    }

    public static List<? extends TypeParameterElement> getTypeParameters(JavacTask task, TreePath path, ExecutableElement element) {
        if (path.getLeaf() instanceof JCTree.JCNewClass) {
            JCTree.JCNewClass newClass = (JCTree.JCNewClass) path.getLeaf();
            //return newClass.getTypeArguments();
        }
        return element.getTypeParameters();
    }
}