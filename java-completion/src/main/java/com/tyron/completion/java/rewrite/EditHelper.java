package com.tyron.completion.java.rewrite;

import static com.tyron.completion.java.patterns.JavacTreePatterns.classTree;
import static com.tyron.completion.java.patterns.JavacTreePatterns.method;
import static com.tyron.completion.java.patterns.JavacTreePatterns.tree;

import androidx.annotation.NonNull;

import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.tyron.completion.java.patterns.ClassTreePattern;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.JavaParserTypesUtil;
import com.tyron.completion.java.util.JavaParserUtil;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class EditHelper {
    final JavacTask task;

    public EditHelper(JavacTask task) {
        this.task = task;
    }

    public TextEdit removeTree(CompilationUnitTree root, Tree remove) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long start = pos.getStartPosition(root, remove);
        long end = pos.getEndPosition(root, remove);
        int startLine = (int) lines.getLineNumber(start);
        int startColumn = (int) lines.getColumnNumber(start);
        Position startPos = new Position(startLine - 1, startColumn - 1);
        int endLine = (int) lines.getLineNumber(end);
        int endColumn = (int) lines.getColumnNumber(end);
        Position endPos = new Position(endLine - 1, endColumn - 1);
        Range range = new Range(startPos, endPos);
        return new TextEdit(range, "");
    }

    /**
     * Prints a given method into a String, adds {@code throws UnsupportedOperationException} to
     * the method body
     * if the source is null, it will get the parameter names from the class file which will be
     * {@code arg1, arg2, arg3}
     *
     * @param method            method to print
     * @param parameterizedType type parameters of this method
     * @param source            the source method, in which the parameter names are fetched
     * @return a string that represents the method
     */
    public static MethodDeclaration printMethod(ExecutableElement method, ExecutableType parameterizedType,
                                     MethodTree source) {
        MethodDeclaration methodDeclaration = JavaParserUtil.toMethodDeclaration(source,
                parameterizedType);
        printMethodInternal(methodDeclaration, method);
        return methodDeclaration;
    }

    public static MethodDeclaration printMethod(ExecutableElement method, ExecutableType parameterizedType,
                                     ExecutableElement source) {
        MethodDeclaration methodDeclaration = JavaParserUtil.toMethodDeclaration(method,
                parameterizedType);
        printMethodInternal(methodDeclaration, source);
        return methodDeclaration;
    }

    private static void printMethodInternal(MethodDeclaration methodDeclaration, ExecutableElement method) {
        methodDeclaration.addMarkerAnnotation(Override.class);
        Optional<AnnotationExpr> recentlyNonNull = methodDeclaration.getAnnotationByName(
                "RecentlyNonNull");
        if (recentlyNonNull.isPresent()) {
            methodDeclaration.remove(recentlyNonNull.get());
            methodDeclaration.addMarkerAnnotation(NonNull.class);
        }
        BlockStmt blockStmt = new BlockStmt();
        if (method.getModifiers().contains(Modifier.ABSTRACT)) {
            methodDeclaration.removeModifier(Keyword.ABSTRACT);
            if (methodDeclaration.getType().isClassOrInterfaceType()) {
                blockStmt.addStatement(new ReturnStmt(new NullLiteralExpr()));
            }
            if (methodDeclaration.getType().isPrimitiveType()) {
                PrimitiveType type = methodDeclaration.getType().asPrimitiveType();
                blockStmt.addStatement(new ReturnStmt(getReturnExpr(type)));
            }
        } else {
            MethodCallExpr methodCallExpr = new MethodCallExpr();
            methodCallExpr.setName(methodDeclaration.getName());
            methodCallExpr.setArguments(methodDeclaration.getParameters().stream()
                    .map(NodeWithSimpleName::getNameAsExpression)
                    .collect(NodeList.toNodeList()));
            methodCallExpr.setScope(new SuperExpr());
            if (methodDeclaration.getType().isVoidType()) {
                blockStmt.addStatement(methodCallExpr);
            } else {
                blockStmt.addStatement(new ReturnStmt(methodCallExpr));
            }
        }
        methodDeclaration.setBody(blockStmt);

    }
    private static Expression getReturnExpr(PrimitiveType type) {
        switch (type.getType()) {
            case BOOLEAN:
                return new BooleanLiteralExpr();
            case BYTE:
            case DOUBLE:
            case CHAR:
            case SHORT:
            case LONG:
            case FLOAT:
            case INT:
                return new IntegerLiteralExpr("0");
            default:
                return new NullLiteralExpr();
        }
    }


    public static String printThrows(@NonNull List<? extends TypeMirror> thrownTypes) {
        StringBuilder types = new StringBuilder();
        for (TypeMirror m : thrownTypes) {
            types.append((types.length() == 0) ? "" : ", ").append(printType(m));
        }
        return "throws " + types;
    }

    public static String printBody(ExecutableElement method, MethodTree source) {
        TypeMirror returnType = method.getReturnType();
        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {

            String body;
            if (method.getParameters().size() == 0) {
                body = "super." + method.getSimpleName() + "();";
            } else {
                String names;
                if (source != null) {
                    names = source.getParameters().stream().map(VariableTree::getName).map(Name::toString).collect(Collectors.joining(", "));
                } else {
                    names = method.getParameters().stream().map(VariableElement::getSimpleName).map(Name::toString).collect(Collectors.joining(", "));
                }
                body = "super." + method.getSimpleName() + "(" + names + ");";
            }

            return returnType.getKind() == TypeKind.VOID ? body : "return " + body;
        }

        switch (returnType.getKind()) {
            case VOID:
                return "";
            case SHORT:
            case CHAR:
            case FLOAT:
            case BYTE:
            case INT:
                return "return 0;";
            case BOOLEAN:
                return "return false;";
            default:
                return "return null;";
        }
    }

    public static String printBody(ExecutableElement method, ExecutableElement source) {
        TypeMirror returnType = method.getReturnType();
        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {

            String body;
            if (method.getParameters().size() == 0) {
                body = "super." + method.getSimpleName() + "();";
            } else {
                String names;
                if (source != null) {
                    names = source.getParameters().stream().map(VariableElement::getSimpleName).map(Name::toString).collect(Collectors.joining(", "));
                } else {
                    names = method.getParameters().stream().map(VariableElement::getSimpleName).map(Name::toString).collect(Collectors.joining(", "));
                }
                body = "super." + method.getSimpleName() + "(" + names + ");";
            }

            return returnType.getKind() == TypeKind.VOID ? body : "return " + body;
        }

        switch (returnType.getKind()) {
            case VOID:
                return "";
            case SHORT:
            case CHAR:
            case FLOAT:
            case BYTE:
            case INT:
                return "return 0;";
            case BOOLEAN:
                return "return false;";
            default:
                return "return null;";
        }
    }

    /**
     * Prints parameters given the source method that contains parameter names
     *
     * @param method element from the .class file
     * @param source element from the .java file
     * @return Formatted string that represents the methods parameters with proper names
     */
    public static String printParameters(ExecutableType method, MethodTree source) {
        StringJoiner join = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterTypes().size(); i++) {
            String type = EditHelper.printType(method.getParameterTypes().get(i)).toString();
            Name name = source.getParameters().get(i).getName();
            join.add(type + " " + name);
        }
        return join.toString();
    }


    /**
     * Prints parameters with the default names eg. {@code arg0, arg1}
     * this is used when the source file of the class isn't found
     *
     * @param method element to print
     * @param source the class file of the method
     * @return Formatted String that represents the parameters of this method
     */
    public static String printParameters(ExecutableType method, ExecutableElement source) {
        StringJoiner join = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterTypes().size(); i++) {
            String type = EditHelper.printType(method.getParameterTypes().get(i)).toString();
            Name name = source.getParameters().get(i).getSimpleName();
            join.add(type + " " + name);
        }
        return join.toString();
    }

    public static com.github.javaparser.ast.type.Type printType(TypeMirror type) {
        return printType(type, false);
    }

    public static com.github.javaparser.ast.type.Type printType(TypeMirror type, boolean fqn) {
        return JavaParserTypesUtil.toType(type);
    }

    public static String printTypeParameters(List<? extends TypeMirror> arguments) {
        StringJoiner join = new StringJoiner(", ");
        for (TypeMirror a : arguments) {
            join.add(printType(a).toString());
        }
        return join.toString();
    }

    public static String printTypeName(TypeElement type) {
        return printTypeName(type, false);
    }

    public static String printTypeName(TypeElement type, boolean fqn) {
        if (type.getEnclosingElement() instanceof TypeElement) {
            return printTypeName((TypeElement) type.getEnclosingElement(), fqn) + "." + type.getSimpleName();
        }

        String s;
        if (type.toString().startsWith("<anonymous") && type instanceof Symbol.ClassSymbol) {
            Symbol.ClassSymbol symbol = (Symbol.ClassSymbol) type;
            s = symbol.type.toString();
        } else if (fqn) {
            s = type.getQualifiedName().toString();
        } else {
            s = type.getSimpleName().toString();
        }
        // anonymous
        if (s.isEmpty()) {
            s = type.asType().toString();
        }

        if (s.startsWith("<anonymous")) {
            s = s.substring("<anonymous ".length(), s.length() - 1);
        }
        if (!fqn) {
            s = ActionUtil.getSimpleName(s);
        }
        return s;
    }


    private static final ClassTreePattern INSIDE_METHOD =  classTree()
            .inside(NewClassTree.class)
            .withParent(method());

    public static int indent(JavacTask task, CompilationUnitTree root, Tree leaf) {
        Trees trees = Trees.instance(task);
        ProcessingContext context = new ProcessingContext();
        context.put("trees", trees);
        context.put("root", root);
        context.put("elements", task.getElements());
        if (tree().inside(NewClassTree.class).accepts(leaf, context)) {
            TreePath parent = TreeUtil.findParentOfType(trees.getPath(root, leaf),
                    NewClassTree.class);
            if (parent != null) {
                leaf = parent.getLeaf();
            }
        }
        return indentInternal(task, root, leaf);
    }

    private static int indentInternal(JavacTask task, CompilationUnitTree root, Tree leaf) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long startClass = pos.getStartPosition(root, leaf);
        long line = lines.getLineNumber(startClass);
        long column = lines.getColumnNumber(startClass);
        long startLine = lines.getStartPosition(line);
        int indent = (int) (startClass - startLine);

        try {
            String contents = root.getSourceFile().getCharContent(true).toString();
            String[] split = contents.split("\n");
            String lineString = split[(int) line - 1];

            int newIndent = 0;
            int spaceCount = 0;
            char[] chars = lineString.toCharArray();
            for (char c : chars) {
                if (c == '\t') {
                    newIndent++;
                } else if (c == ' ') {
                    if (spaceCount == 3) {
                        spaceCount = 0;
                        newIndent++;
                    } else {
                        spaceCount++;
                    }
                } else {
                    break;
                }
            }
            return newIndent;
        } catch (Throwable e) {
            // ignored, use the old indent
        } return indent;
    }


    public static Position insertBefore(JavacTask task, CompilationUnitTree root, Tree member) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long start = pos.getStartPosition(root, member);
        int line = (int) lines.getLineNumber(start);
        return new Position(line - 1, 0);
    }

    public static Position insertAfter(JavacTask task, CompilationUnitTree root, Tree member) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long end = pos.getEndPosition(root, member);
        int line = (int) lines.getLineNumber(end);
        return new Position(line, 0);
    }

    public static Position insertAtEndOfClass(JavacTask task, CompilationUnitTree root,
                                              ClassTree leaf) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long end = pos.getEndPosition(root, leaf);
        int line = (int) lines.getLineNumber(end);
        return new Position(line - 1, 0);
    }
}