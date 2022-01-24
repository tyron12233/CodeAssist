package com.tyron.completion.java.util;

import static com.tyron.completion.java.rewrite.EditHelper.printBody;
import static com.tyron.completion.java.rewrite.EditHelper.printParameters;
import static com.tyron.completion.java.rewrite.EditHelper.printThrows;

import com.tyron.completion.java.rewrite.EditHelper;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.source.tree.MethodTree;

public class PrintHelper {

    /**
     * Prints a given method into a String, adds {@code throws UnsupportedOperationException} to the method body
     * if the source is null, it will get the parameter names from the class file which will be {@code arg1, arg2, arg3}
     *
     * Not to be confused with {@link EditHelper#printMethod(ExecutableElement, ExecutableType, MethodTree)}
     * This does not convert the method into a {@link com.github.javaparser.ast.body.MethodDeclaration}
     *
     * @param method method to print
     * @param parameterizedType type parameters of this method
     * @param source the source method, in which the parameter names are fetched
     * @return a string that represents the method
     */
    public static String printMethod(ExecutableElement method, ExecutableType parameterizedType, MethodTree source) {
        StringBuilder buf = new StringBuilder();
        buf.append("@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        }
        if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }

        buf.append(EditHelper.printType(parameterizedType.getReturnType())).append(" ");

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

    public static String printMethod(ExecutableElement method, ExecutableType parameterizedType, ExecutableElement source) {
        StringBuilder buf = new StringBuilder();
        buf.append("@Override\n");
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            buf.append("public ");
        }
        if (method.getModifiers().contains(Modifier.PROTECTED)) {
            buf.append("protected ");
        }

        buf.append(EditHelper.printType(parameterizedType.getReturnType())).append(" ");

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
}
