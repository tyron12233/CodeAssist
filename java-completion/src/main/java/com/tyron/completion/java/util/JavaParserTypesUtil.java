package com.tyron.completion.java.util;

import static com.github.javaparser.utils.PositionUtils.sortByBeginPosition;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.ast.visitor.VoidVisitor;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;

import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.NoType;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.type.TypeVariable;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.WildcardTree;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class JavaParserTypesUtil {

    public interface NeedFqnDelegate {
        boolean needsFqn(String name);
    }

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
        if (typeMirror instanceof org.openjdk.javax.lang.model.type.IntersectionType) {
            return toIntersectionType((org.openjdk.javax.lang.model.type.IntersectionType) typeMirror);
        }
        if (typeMirror instanceof org.openjdk.javax.lang.model.type.WildcardType) {
            return toWildcardType((org.openjdk.javax.lang.model.type.WildcardType) typeMirror);
        }
        if (typeMirror instanceof org.openjdk.javax.lang.model.type.DeclaredType) {
            return toClassOrInterfaceType((DeclaredType) typeMirror);
        }
        if (typeMirror instanceof org.openjdk.javax.lang.model.type.TypeVariable) {
            return toType(((TypeVariable) typeMirror));
        }
        if (typeMirror instanceof NoType) {
            return new VoidType();
        }
        return null;
    }

    public static IntersectionType toIntersectionType(org.openjdk.javax.lang.model.type.IntersectionType type) {
        NodeList<ReferenceType> collect =
                type.getBounds().stream().map(JavaParserTypesUtil::toType)
                        .map(it -> ((ReferenceType) it))
                        .collect(NodeList.toNodeList());
        return new IntersectionType(collect);
    }

    public static Type toType(TypeVariable typeVariable) {
        TypeParameter typeParameter = new TypeParameter();
        TypeMirror upperBound = typeVariable.getUpperBound();
        Type type = toType(upperBound);
        if (type != null && type.isIntersectionType()) {
            typeParameter.setTypeBound(type.asIntersectionType().getElements().stream()
                    .filter(Type::isClassOrInterfaceType)
                    .map(Type::asClassOrInterfaceType)
                    .collect(NodeList.toNodeList()));
        }
        typeParameter.setName(typeVariable.toString());
        return typeParameter;
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

    public static WildcardType toWildcardType(WildcardTree tree) {
        WildcardType wildcardType = new WildcardType();
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

    public static ClassOrInterfaceType toClassOrInterfaceType(DeclaredType type) {
        ClassOrInterfaceType classOrInterfaceType = new ClassOrInterfaceType();
        if (!type.getTypeArguments().isEmpty()) {
            classOrInterfaceType.setTypeArguments(type.getTypeArguments().stream().map(JavaParserTypesUtil::toType).filter(Objects::nonNull).collect(NodeList.toNodeList()));
        }
        if (!type.asElement().toString().isEmpty()) {
            classOrInterfaceType.setName(type.asElement().toString());
        }
        return classOrInterfaceType;
    }

    public static String getName(Type type, NeedFqnDelegate needFqnDelegate) {
        PrinterConfiguration configuration = new DefaultPrinterConfiguration();
        JavaPrettyPrinterVisitor visitor = new JavaPrettyPrinterVisitor(configuration) {
            @Override
            public void visit(SimpleName n, Void arg) {
                printOrphanCommentsBeforeThisChildNode(n);
                printComment(n.getComment(), arg);

                String identifier = n.getIdentifier();
                if (needFqnDelegate.needsFqn(identifier)) {
                    printer.print(identifier);
                } else {
                    printer.print(ActionUtil.getSimpleName(identifier));
                }
            }
        };
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter(t -> visitor, configuration);
        return prettyPrinter.print(type);
    }

    public static String getSimpleName(Type type) {
        PrinterConfiguration configuration = new DefaultPrinterConfiguration();
        JavaPrettyPrinterVisitor visitor = new JavaPrettyPrinterVisitor(configuration);
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter(t -> visitor, configuration);
        return prettyPrinter.print(type);
    }
}