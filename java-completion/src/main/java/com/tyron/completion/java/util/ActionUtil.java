package com.tyron.completion.java.util;

import static com.sun.source.tree.Tree.Kind.ANNOTATION;
import static com.sun.source.tree.Tree.Kind.ARRAY_TYPE;
import static com.sun.source.tree.Tree.Kind.ASSIGNMENT;
import static com.sun.source.tree.Tree.Kind.BLOCK;
import static com.sun.source.tree.Tree.Kind.BOOLEAN_LITERAL;
import static com.sun.source.tree.Tree.Kind.CATCH;
import static com.sun.source.tree.Tree.Kind.CLASS;
import static com.sun.source.tree.Tree.Kind.DO_WHILE_LOOP;
import static com.sun.source.tree.Tree.Kind.EXPRESSION_STATEMENT;
import static com.sun.source.tree.Tree.Kind.FOR_LOOP;
import static com.sun.source.tree.Tree.Kind.IDENTIFIER;
import static com.sun.source.tree.Tree.Kind.IF;
import static com.sun.source.tree.Tree.Kind.IMPORT;
import static com.sun.source.tree.Tree.Kind.INTERFACE;
import static com.sun.source.tree.Tree.Kind.INT_LITERAL;
import static com.sun.source.tree.Tree.Kind.LAMBDA_EXPRESSION;
import static com.sun.source.tree.Tree.Kind.LONG_LITERAL;
import static com.sun.source.tree.Tree.Kind.MEMBER_SELECT;
import static com.sun.source.tree.Tree.Kind.METHOD;
import static com.sun.source.tree.Tree.Kind.METHOD_INVOCATION;
import static com.sun.source.tree.Tree.Kind.NEW_CLASS;
import static com.sun.source.tree.Tree.Kind.PACKAGE;
import static com.sun.source.tree.Tree.Kind.PARAMETERIZED_TYPE;
import static com.sun.source.tree.Tree.Kind.PARENTHESIZED;
import static com.sun.source.tree.Tree.Kind.PRIMITIVE_TYPE;
import static com.sun.source.tree.Tree.Kind.RETURN;
import static com.sun.source.tree.Tree.Kind.STRING_LITERAL;
import static com.sun.source.tree.Tree.Kind.SWITCH;
import static com.sun.source.tree.Tree.Kind.THROW;
import static com.sun.source.tree.Tree.Kind.TRY;
import static com.sun.source.tree.Tree.Kind.TYPE_CAST;
import static com.sun.source.tree.Tree.Kind.UNARY_MINUS;
import static com.sun.source.tree.Tree.Kind.UNARY_PLUS;
import static com.sun.source.tree.Tree.Kind.VARIABLE;
import static com.sun.source.tree.Tree.Kind.WHILE_LOOP;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.rewrite.EditHelper;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ActionUtil {

    private static final Pattern DIGITS_PATTERN = Pattern.compile("^(.+?)(\\d+)$");
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");

    private static final Set<Tree.Kind> DISALLOWED_KINDS_INTRODUCE_LOCAL_VARIABLE =
            ImmutableSet.of(IMPORT, PACKAGE, INTERFACE, METHOD, ANNOTATION, THROW,
                    WHILE_LOOP, DO_WHILE_LOOP, FOR_LOOP, IF, TRY, CATCH,
                    UNARY_PLUS, UNARY_MINUS, RETURN, LAMBDA_EXPRESSION, ASSIGNMENT
            );
    private static final Set<Tree.Kind> CHECK_PARENT_KINDS_INTRODUCE_LOCAL_VARIABLE =
            ImmutableSet.of(
                    STRING_LITERAL, ARRAY_TYPE, PARENTHESIZED, MEMBER_SELECT, PRIMITIVE_TYPE,
                    IDENTIFIER, BLOCK, TYPE_CAST, PARAMETERIZED_TYPE,
                    INT_LITERAL, LONG_LITERAL, BOOLEAN_LITERAL);

    public static TreePath canIntroduceLocalVariable(TreePath path) {
        if (path == null) {
            return null;
        }
        Tree.Kind kind = path.getLeaf().getKind();
        TreePath parent = path.getParentPath();

        // = new ..
        if (kind == NEW_CLASS && parent.getLeaf().getKind() == VARIABLE) {
            return null;
        }
        if (DISALLOWED_KINDS_INTRODUCE_LOCAL_VARIABLE.contains(kind)) {
            return null;
        }

        if (path.getLeaf() instanceof JCTree.JCVariableDecl) {
            return null;
        }

        if (CHECK_PARENT_KINDS_INTRODUCE_LOCAL_VARIABLE.contains(kind)) {
            return canIntroduceLocalVariable(parent);
        }

        if (path.getLeaf() instanceof ClassTree && parent.getLeaf() instanceof NewClassTree) {
            return null;
        }

        if (path.getLeaf() instanceof ClassTree) {
            return null;
        }

        if (kind == METHOD_INVOCATION) {
            JCTree.JCMethodInvocation methodInvocation = (JCTree.JCMethodInvocation) path.getLeaf();
            // void return type
            if (isVoid(methodInvocation)) {
                return null;
            }

            if (parent.getLeaf().getKind() == EXPRESSION_STATEMENT) {
                return path;
            }

            return canIntroduceLocalVariable(parent);
        }

        if (parent == null) {
            return null;
        }

        TreePath grandParent = parent.getParentPath();
        if (parent.getLeaf() instanceof ParenthesizedTree) {
            if (grandParent.getLeaf() instanceof IfTree) {
                return null;
            }
            if (grandParent.getLeaf() instanceof WhileLoopTree) {
                return null;
            }
            if (grandParent.getLeaf() instanceof ForLoopTree) {
                return null;
            }
        }

        // can't introduce a local variable on a lambda expression
        // eg. run(() -> something());
        if (parent.getLeaf() instanceof JCTree.JCLambda) {
            return null;
        }

        if (path.getLeaf() instanceof NewClassTree) {
            // run(new Runnable() { });
            if (parent.getLeaf() instanceof MethodInvocationTree) {
                return null;
            }
        }

        return path;
    }

    private static boolean isVoid(JCTree.JCMethodInvocation methodInvocation) {
        if (methodInvocation.type == null) {
            // FIXME: get the type from elements using the tree
            return false;
        }
        if (!methodInvocation.type.isPrimitive()) {
            return methodInvocation.type.isPrimitiveOrVoid();
        }
        return false;
    }

    public static TreePath findSurroundingPath(TreePath path) {
        TreePath parent = path.getParentPath();
        TreePath grandParent = parent.getParentPath();

        if (path.getLeaf() instanceof ExpressionStatementTree) {
            return path;
        }

        if (path.getLeaf() instanceof MethodInvocationTree) {
            return findSurroundingPath(parent);
        }

        if (path.getLeaf() instanceof MemberSelectTree) {
            return findSurroundingPath(parent);
        }

        if (path.getLeaf() instanceof IdentifierTree) {
            return findSurroundingPath(parent);
        }

        if (parent.getLeaf() instanceof JCTree.JCVariableDecl) {
            return parent;
        }
        // inside if parenthesis
        if (parent.getLeaf() instanceof ParenthesizedTree) {
            if (grandParent.getLeaf() instanceof IfTree) {
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

    public static TypeMirror getReturnType(JavacTask task, TreePath path,
                                           ExecutableElement element) {
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
        return needsFqn(root.getImports().stream().map(ImportTree::getQualifiedIdentifier).map(Tree::toString).collect(Collectors.toSet()), className);
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

        return hasImport(root.getImports().stream().map(ImportTree::getQualifiedIdentifier).map(Tree::toString).collect(Collectors.toSet()), className);
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

    public static boolean containsVariableAtScope(String name, long position, CompileTask parse) {
        TreePath scan = new FindCurrentPath(parse.task).scan(parse.root(), position + 1);
        if (scan == null) {
            return false;
        }
        Scope scope = Trees.instance(parse.task).getScope(scan);
        Iterable<? extends Element> localElements = scope.getLocalElements();
        for (Element element : localElements) {
            if (name.contentEquals(element.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsVariableAtScope(String name, CompileTask parse, TreePath path) {
        Scope scope = Trees.instance(parse.task).getScope(path);
        Iterable<? extends Element> localElements = scope.getLocalElements();
        for (Element element : localElements) {
            if (element.getKind() != ElementKind.LOCAL_VARIABLE &&
                    !element.getKind().isField()) {
                continue;
            }
            if (name.contentEquals(element.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    public static String getVariableName(String name) {
        Matcher matcher = DIGITS_PATTERN.matcher(name);
        if (matcher.matches()) {
            String variableName = matcher.group(1);
            String stringNumber = matcher.group(2);
            if (stringNumber == null) {
                stringNumber = "0";
            }
            int number = Integer.parseInt(stringNumber) + 1;
            return variableName + number;
        }
        return name + "1";
    }
    public static List<String> guessNamesFromType(TypeMirror typeMirror) {
        List<String> list = new ArrayList<>();
        if (typeMirror.getKind() == TypeKind.DECLARED) {
            DeclaredType type = (DeclaredType) typeMirror;

            String typeName = guessNameFromTypeName(getSimpleName(type.toString()));
            String[] types = getSimpleName(type.toString())
                    .split(CAMEL_CASE_PATTERN.pattern());
            if (types.length != 0) {
                for (int i = 0; i < types.length; i++) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(guessNameFromTypeName(types[i]));
                    if (i + 1 < types.length) {
                        for (int j = i + 1; j < types.length; j++) {
                            sb.append(Character.toUpperCase(types[j].charAt(0)))
                                    .append(types[j].substring(1));
                        }
                    }
                    list.add(sb.toString());
                }
            } else {
                list.add(typeName);
            }

            List<String> typeNames = new ArrayList<>();
            for (TypeMirror typeArgument : type.getTypeArguments()) {
                String s = guessNameFromTypeName(getSimpleName(typeArgument.toString()));
                if (!s.isEmpty()) {
                    typeNames.add(s);
                }
            }

            if (!typeNames.isEmpty()) {
                StringBuilder stringBuilder = new StringBuilder();
                for (int i = 0; i < typeNames.size(); i++) {
                    String name = typeNames.get(i);
                    if (i == 0) {
                        name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
                    } else {
                        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    }
                    stringBuilder.append(name);
                }
                stringBuilder.append(Character.toUpperCase(typeName.charAt(0)))
                        .append(typeName.substring(1));
                list.add(stringBuilder.toString());
            }
        }
        return list;
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
            }
            name = ActionUtil.getSimpleName(name);
            return guessNameFromTypeName(name);
        }
        return null;
    }

    public static String guessNameFromTypeName(String name) {
        String lowercase = "" + Character.toLowerCase(name.charAt(0)) + name.substring(1);
        if (!SourceVersion.isName(lowercase)) {
            String uppercase = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            char c = lowercase.charAt(0);
            if ("aeiouAEIOU".indexOf(c) != -1) {
                return "an" + uppercase;
            }  else {
                return "a" + uppercase;
            }
        }
        return lowercase;
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
        return guessNameFromTypeName(methodName);
    }

    /**
     * Get all the possible fully qualified names that may be imported
     *
     * @param type method to scan
     * @return Set of fully qualified names not including the diamond operator
     */
    public static Set<String> getTypesToImport(ExecutableType type) {
        Set<String> types = new HashSet<>();

        TypeMirror returnType = type.getReturnType();
        if (returnType != null) {
            if (returnType.getKind() != TypeKind.VOID
                    && returnType.getKind() != TypeKind.TYPEVAR
                    && !returnType.getKind().isPrimitive()) {
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
            if (t.getKind().isPrimitive()) {
                continue;
            }
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

    public static List<? extends TypeParameterElement> getTypeParameters(JavacTask task,
                                                                         TreePath path,
                                                                         ExecutableElement element) {
        if (path.getLeaf() instanceof JCTree.JCNewClass) {
            JCTree.JCNewClass newClass = (JCTree.JCNewClass) path.getLeaf();
            //return newClass.getTypeArguments();
        }
        return element.getTypeParameters();
    }
}