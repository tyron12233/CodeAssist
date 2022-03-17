package com.tyron.completion.java.util;

import static com.tyron.completion.java.rewrite.EditHelper.printBody;
import static com.tyron.completion.java.rewrite.EditHelper.printParameters;
import static com.tyron.completion.java.rewrite.EditHelper.printThrows;

import com.tyron.completion.java.rewrite.EditHelper;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.TreeInfo;

import java.util.List;
import java.util.StringJoiner;

/**
 * Converts the given Element directly into String without converting first through
 * {@link com.github.javaparser.JavaParser}
 */
public class PrintHelper {

    /**
     * Prints a given method into a String, adds {@code throws UnsupportedOperationException} to
     * the method body
     * if the source is null, it will get the parameter names from the class file which will be
     * {@code arg1, arg2, arg3}
     * <p>
     * Not to be confused with
     * {@link EditHelper#printMethod(ExecutableElement, ExecutableType, MethodTree)}
     * This does not convert the method into a
     * {@link com.github.javaparser.ast.body.MethodDeclaration}
     *
     * @param method            method to print
     * @param parameterizedType type parameters of this method
     * @param source            the source method, in which the parameter names are fetched
     * @return a string that represents the method
     */
    public static String printMethod(ExecutableElement method,
                                     ExecutableType parameterizedType,
                                     MethodTree source) {
        StringBuilder buf = new StringBuilder();
        buf.append("@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        }
        if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }

        buf.append(PrintHelper.printType(parameterizedType.getReturnType())).append(" ");

        buf.append(method.getSimpleName()).append("(");
        if (source == null) {
            buf.append(printParameters(parameterizedType, method));
        } else {
            buf.append(printParameters(parameterizedType, source));
        }
        buf.append(") {\n\t");
        buf.append(printBody(method, source));
        buf.append("\n}");
        return buf.toString();
    }

    public static String printMethod(ExecutableElement method,
                                     ExecutableType parameterizedType,
                                     ExecutableElement source) {
        StringBuilder buf = new StringBuilder();
        buf.append("@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        }
        if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }

        buf.append(PrintHelper.printType(parameterizedType.getReturnType())).append(" ");

        buf.append(method.getSimpleName()).append("(");
        if (source == null) {
            buf.append(printParameters(parameterizedType, method));
        } else {
            buf.append(printParameters(parameterizedType, source));
        }
        buf.append(") ");
        if (method.getThrownTypes() != null && !method.getThrownTypes().isEmpty()) {
            buf.append(printThrows(method.getThrownTypes()));
            buf.append(" ");
        }
        buf.append("{\n\t");
        buf.append(printBody(method, source));
        buf.append("\n}");
        return buf.toString();
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
            String type = printType(method.getParameterTypes().get(i));
            Name name = source.getParameters().get(i).getName();
            join.add(type + " " + name);
        }
        return join.toString();
    }

    public static String printParameters(ExecutableType method, ExecutableElement source) {
        StringJoiner join = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterTypes().size(); i++) {
            String type = printType(method.getParameterTypes().get(i));
            Name name = source.getParameters().get(i).getSimpleName();
            join.add(type + " " + name);
        }
        return join.toString();
    }

    public static String printType(TypeMirror type) {
        return printType(type, false);
    }

    public static String printType(TypeMirror type, boolean fqn) {
        if (type instanceof DeclaredType) {
            DeclaredType declared = (DeclaredType) type;
            if (declared instanceof Type.ClassType) {
                Type.ClassType classType = (Type.ClassType) declared;
                if (classType.all_interfaces_field != null &&
                    !classType.all_interfaces_field.isEmpty()) {
                    Type next = classType.all_interfaces_field.get(0);
                    declared = (DeclaredType) next;
                }
            }
            String string = EditHelper.printTypeName((TypeElement) declared.asElement(), fqn);
            if (!declared.getTypeArguments().isEmpty()) {
                string = string + "<" + printTypeParameters(declared.getTypeArguments()) + ">";
            }
            return string;
        } else if (type instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) type;
            if (!fqn) {
                return printType(arrayType.getComponentType()) + "[]";
            } else {
                return arrayType.toString();
            }
        } else if (type instanceof Type.TypeVar) {
            Type.TypeVar typeVar = ((Type.TypeVar) type);
            if (typeVar.isCaptured()) {
                return "? extends " + printType(typeVar.getUpperBound(), fqn);
            }
            return typeVar.toString();
        } else {
            if (fqn) {
                return type.toString();
            } else {
                return ActionUtil.getSimpleName(type.toString());
            }
        }
    }

    public static String printTypeParameters(List<? extends TypeMirror> arguments) {
        StringJoiner join = new StringJoiner(", ");
        for (TypeMirror a : arguments) {
            join.add(printType(a));
        }
        return join.toString();
    }

}
