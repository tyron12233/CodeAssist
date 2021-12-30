package com.tyron.completion.java.util;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;

import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.Tree;

import java.util.Objects;

public class JavaParserTypesUtil {

    public static Type toType(Tree type) {
        return null;
    }
    public static Type toType(TypeMirror typeMirror) {
        if (typeMirror.getKind() == TypeKind.ARRAY) {
            return toArrayType((org.openjdk.javax.lang.model.type.ArrayType) typeMirror);
        }
        if (typeMirror.getKind().isPrimitive()) {
            return toPrimitiveType((org.openjdk.javax.lang.model.type.PrimitiveType) typeMirror);
        }
        if (typeMirror instanceof org.openjdk.tools.javac.code.Type.ClassType) {
            return toClassOrInterfaceType(((org.openjdk.tools.javac.code.Type.ClassType) typeMirror));
        }
        if (typeMirror instanceof org.openjdk.javax.lang.model.type.WildcardType) {
            return toWildcardType((org.openjdk.javax.lang.model.type.WildcardType) typeMirror);
        }
        return null;
    }

    public static WildcardType toWildcardType(org.openjdk.javax.lang.model.type.WildcardType type) {
        WildcardType wildcardType = new WildcardType();
        if (type.getSuperBound() != null) {
            wildcardType.setSuperType((ReferenceType) toType(type.getSuperBound()));
        }
        if (type.getExtendsBound() != null) {
            wildcardType.setExtendedType((ReferenceType) toType(type.getExtendsBound()));
        }
        return wildcardType;
    }

    public static PrimitiveType toPrimitiveType(org.openjdk.javax.lang.model.type.PrimitiveType type) {
        PrimitiveType.Primitive primitive = PrimitiveType.Primitive.valueOf(type.getKind().name());
        return new PrimitiveType(primitive);
    }

    public static ArrayType toArrayType(org.openjdk.javax.lang.model.type.ArrayType type) {
        Type componentType = toType(type.getComponentType());
        return new ArrayType(componentType);
    }

    public static ClassOrInterfaceType toClassOrInterfaceType(org.openjdk.tools.javac.code.Type.ClassType type) {
        ClassOrInterfaceType classOrInterfaceType = new ClassOrInterfaceType();
        if (!type.getTypeArguments().isEmpty()) {
            classOrInterfaceType.setTypeArguments(type.getTypeArguments().stream().map(JavaParserTypesUtil::toType).filter(Objects::nonNull).collect(NodeList.toNodeList()));
        }
        classOrInterfaceType.setName(type.tsym.name.toString());
        return classOrInterfaceType;
    }
}
