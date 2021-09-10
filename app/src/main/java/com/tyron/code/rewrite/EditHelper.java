package com.tyron.code.rewrite;

import com.tyron.code.model.Position;
import com.tyron.code.model.Range;
import com.tyron.code.model.TextEdit;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.Name;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.LineMap;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.Trees;

import java.util.List;
import java.util.StringJoiner;

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

    public static String printMethod(ExecutableElement method, ExecutableType parameterizedType, MethodTree source) {
        StringBuilder buf = new StringBuilder();
        // TODO leading \n is extra, but needed for indent replaceAll trick
        buf.append("\n@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        }
        if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }
        buf.append(EditHelper.printType(parameterizedType.getReturnType())).append(" ");
        buf.append(method.getSimpleName()).append("(");
        buf.append(printParameters(parameterizedType, source));
        buf.append(") {\n    // TODO\n}");
        return buf.toString();
    }

    public static String printMethod(ExecutableElement method, ExecutableType parameterizedType, ExecutableElement source) {
        StringBuilder buf = new StringBuilder();

        buf.append("\n@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        } else if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }
        buf.append(EditHelper.printType(parameterizedType.getReturnType())).append(' ');
        buf.append('(');
        buf.append(method.getSimpleName()).append(' ');
        buf.append(printParameters(parameterizedType, source));
        buf.append(") {\n\tthrow new UnsupportedOperationException(\"TODO\");\n}");
        return buf.toString();
    }

    private static String printParameters(ExecutableType method, MethodTree source) {
        StringJoiner join = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterTypes().size(); i++) {
            String type = EditHelper.printType(method.getParameterTypes().get(i));
            Name name = source.getParameters().get(i).getName();
            join.add(type + " " + name);
        }
        return join.toString();
    }

    private static String printParameters(ExecutableType method, ExecutableElement source) {
        StringJoiner join = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterTypes().size(); i++) {
            String type = EditHelper.printType(method.getParameterTypes().get(i));
            Name name = source.getParameters().get(i).getSimpleName();
            join.add(type + " " + name);
        }
        return join.toString();
    }

    static String printType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declared = (DeclaredType) type;
            String string = printTypeName((TypeElement) declared.asElement());
            if (!declared.getTypeArguments().isEmpty()) {
                string = string + "<" + printTypeParameters(declared.getTypeArguments()) + ">";
            }
            return string;
        } else if (type instanceof ArrayType) {
            ArrayType array = (ArrayType) type;
            return printType(array.getComponentType()) + "[]";
        } else {
            return type.toString();
        }
    }

    private static String printTypeParameters(List<? extends TypeMirror> arguments) {
        StringJoiner join = new StringJoiner(", ");
        for (TypeMirror a : arguments) {
            join.add(printType(a));
        }
        return join.toString();
    }

    static String printTypeName(TypeElement type) {
        if (type.getEnclosingElement() instanceof TypeElement) {
            return printTypeName((TypeElement) type.getEnclosingElement()) + "." + type.getSimpleName();
        }
        return type.getSimpleName().toString();
    }

    static int indent(JavacTask task, CompilationUnitTree root, ClassTree leaf) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long startClass = pos.getStartPosition(root, leaf);
        long startLine = lines.getStartPosition(lines.getLineNumber(startClass));
        return (int) (startClass - startLine);
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

    public static Position insertAtEndOfClass(JavacTask task, CompilationUnitTree root, ClassTree leaf) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = root.getLineMap();
        long end = pos.getEndPosition(root, leaf);
        int line = (int) lines.getLineNumber(end);
        return new Position(line - 1, 0);
    }
}