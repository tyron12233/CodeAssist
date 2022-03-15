package com.tyron.code.language.java;

import com.tyron.code.analyzer.semantic.TokenType;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import com.sun.tools.javac.code.Symbol;

public class JavaTokenTypes {

    public static final TokenType FIELD = TokenType.create("variable.other.object.property.java");
    public static final TokenType CONSTANT = TokenType.create("variable.other.constant");
    public static final TokenType PARAMETER = TokenType.create("variable.parameter");
    public static final TokenType CLASS = TokenType.create("entity.name.type.class");
    public static final TokenType METHOD_CALL = TokenType.create("meta.method-call");
    public static final TokenType METHOD_DECLARATION = TokenType.create("entity.name.function.member");
    public static final TokenType VARIABLE = TokenType.create("entity.name.variable");
    public static final TokenType CONSTRUCTOR = TokenType.create("class.instance.constructor");
    public static final TokenType ANNOTATION = TokenType.create("storage.type.annotation");

    public static TokenType getApplicableType(Element element) {
        if (element == null) {
            return null;
        }

        switch (element.getKind()) {
            case LOCAL_VARIABLE:
                Symbol.VarSymbol varSymbol = (Symbol.VarSymbol) element;
                if (varSymbol.getModifiers().contains(Modifier.FINAL)) {
                    return CONSTANT;
                }
                return VARIABLE;
            case METHOD:
                Symbol.MethodSymbol methodSymbol = ((Symbol.MethodSymbol) element);
                if (methodSymbol.isConstructor()) {
                    return getApplicableType(methodSymbol.getEnclosingElement());
                }
                return METHOD_DECLARATION;
            case FIELD:
                VariableElement variableElement = ((VariableElement) element);
                if (variableElement.getModifiers().contains(Modifier.FINAL)) {
                    return CONSTANT;
                }
                return FIELD;
            case CLASS:
                return CLASS;
            case CONSTRUCTOR:
                return CONSTRUCTOR;
            case PARAMETER:
                return PARAMETER;
            case ANNOTATION_TYPE:
                return ANNOTATION;
            default:
                return null;
        }
    }
}
