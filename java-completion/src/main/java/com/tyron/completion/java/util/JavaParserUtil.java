package com.tyron.completion.java.util;

import static com.tyron.completion.java.util.JavaParserTypesUtil.toType;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.ReceiverParameter;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.tyron.completion.java.rewrite.EditHelper;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.TypeParameterElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.TypeParameterTree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.tools.javac.code.Type.ClassType;

import java.io.Serializable;
import java.util.Objects;
import java.util.stream.IntStream;

public class JavaParserUtil {

    public static MethodDeclaration toMethodDeclaration(ExecutableElement method, ExecutableType type) {
        MethodDeclaration methodDeclaration = new MethodDeclaration();
        methodDeclaration.setType(toType(type.getReturnType()));
        methodDeclaration.setDefault(method.isDefault());
        methodDeclaration.setName(method.getSimpleName().toString());
        methodDeclaration.setModifiers(method.getModifiers().stream()
                .map(modifier -> Modifier.Keyword.valueOf(modifier.name()))
                .toArray(Modifier.Keyword[]::new));
        methodDeclaration.setParameters(IntStream.range(0, method.getParameters().size())
                .mapToObj(i -> toParameter(type.getParameterTypes().get(i), method.getParameters().get(i)))
                .collect(NodeList.toNodeList()));
        methodDeclaration.setTypeParameters(method.getTypeParameters().stream()
                .map(JavaParserUtil::toTypeParameter)
                .collect(NodeList.toNodeList()));
        return methodDeclaration;
    }

    public static MethodDeclaration toMethodDeclaration(MethodTree method, ExecutableType type) {
        MethodDeclaration methodDeclaration = new MethodDeclaration();
        methodDeclaration.setName(method.getName().toString());
        methodDeclaration.setType(toType(type.getReturnType()));
        methodDeclaration.setModifiers(method.getModifiers().getFlags().stream()
                .map(JavaParserUtil::toModifier)
                .collect(NodeList.toNodeList()));
        methodDeclaration.setParameters(IntStream.range(0, method.getParameters().size())
                .mapToObj(i -> toParameter(type.getParameterTypes().get(i), method.getParameters().get(i)))
                .collect(NodeList.toNodeList()));
        methodDeclaration.setTypeParameters(method.getTypeParameters().stream()
                .map(JavaParserUtil::toTypeParameter)
                .collect(NodeList.toNodeList()));
        if (method.getReceiverParameter() != null) {
            methodDeclaration.setReceiverParameter(toReceiverParameter(method.getReceiverParameter()));
        }
        return methodDeclaration;
    }

    public static Modifier toModifier(org.openjdk.javax.lang.model.element.Modifier modifier) {
        return new Modifier(Modifier.Keyword.valueOf(modifier.name()));
    }

    /**
     * Convert a parameter into {@link Parameter} object. This method is called from
     * compiled class files, giving inaccurate parameter names
     */
    public static Parameter toParameter(TypeMirror type, VariableElement name) {
        Parameter parameter = new Parameter();
        parameter.setType(EditHelper.printType(type));
        parameter.setName(name.getSimpleName().toString());
        return parameter;
    }

    /**
     * Convert a parameter into {@link Parameter} object. This method is called from
     * source files, giving their accurate names.
     */
    public static Parameter toParameter(TypeMirror type, VariableTree name) {
        Parameter parameter = new Parameter();
        parameter.setType(EditHelper.printType(type));
        parameter.setName(name.getName().toString());
        return parameter;
    }

    public static TypeParameter toTypeParameter(TypeParameterElement type) {
        return StaticJavaParser.parseTypeParameter(type.toString());
    }

    public static TypeParameter toTypeParameter(TypeParameterTree type) {
        return StaticJavaParser.parseTypeParameter(type.toString());
    }

    public static ReceiverParameter toReceiverParameter(VariableTree parameter) {
        ReceiverParameter receiverParameter = new ReceiverParameter();
        receiverParameter.setName(parameter.getName().toString());
        receiverParameter.setType(toType(parameter.getType()));
        return receiverParameter;
    }
}
