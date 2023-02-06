package com.tyron.code.highlighter;

import static com.tyron.code.highlighter.attributes.TextAttributesKeyUtils.createTextAttributesKey;

import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;

/**
 * Highlighting text attributes for Java language.
 */
public final class JavaHighlightingColors {
    public static final TextAttributesKey LINE_COMMENT = createTextAttributesKey("JAVA_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey JAVA_BLOCK_COMMENT = createTextAttributesKey("JAVA_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey DOC_COMMENT = createTextAttributesKey("JAVA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT);
    public static final TextAttributesKey KEYWORD = createTextAttributesKey("JAVA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey NUMBER = createTextAttributesKey("JAVA_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING = createTextAttributesKey("JAVA_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey OPERATION_SIGN = createTextAttributesKey("JAVA_OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey PARENTHESES = createTextAttributesKey("JAVA_PARENTH", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey BRACKETS = createTextAttributesKey("JAVA_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey BRACES = createTextAttributesKey("JAVA_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey COMMA = createTextAttributesKey("JAVA_COMMA", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = createTextAttributesKey("JAVA_DOT", DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey JAVA_SEMICOLON = createTextAttributesKey("JAVA_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
    public static final TextAttributesKey DOC_COMMENT_TAG = createTextAttributesKey("JAVA_DOC_TAG", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG);
    public static final TextAttributesKey DOC_COMMENT_MARKUP = createTextAttributesKey("JAVA_DOC_MARKUP", DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP);
    public static final TextAttributesKey DOC_COMMENT_TAG_VALUE = createTextAttributesKey("DOC_COMMENT_TAG_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE);
    public static final TextAttributesKey VALID_STRING_ESCAPE = createTextAttributesKey("JAVA_VALID_STRING_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE);
    public static final TextAttributesKey INVALID_STRING_ESCAPE = createTextAttributesKey("JAVA_INVALID_STRING_ESCAPE", DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE);
    public static final TextAttributesKey LOCAL_VARIABLE_ATTRIBUTES = createTextAttributesKey("LOCAL_VARIABLE_ATTRIBUTES", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey PARAMETER_ATTRIBUTES = createTextAttributesKey("PARAMETER_ATTRIBUTES", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey LAMBDA_PARAMETER_ATTRIBUTES = createTextAttributesKey("LAMBDA_PARAMETER_ATTRIBUTES", PARAMETER_ATTRIBUTES);
    public static final TextAttributesKey REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES = createTextAttributesKey("REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES", DefaultLanguageHighlighterColors.REASSIGNED_LOCAL_VARIABLE);
    public static final TextAttributesKey REASSIGNED_PARAMETER_ATTRIBUTES = createTextAttributesKey("REASSIGNED_PARAMETER_ATTRIBUTES", DefaultLanguageHighlighterColors.REASSIGNED_PARAMETER);
    public static final TextAttributesKey INSTANCE_FIELD_ATTRIBUTES = createTextAttributesKey("INSTANCE_FIELD_ATTRIBUTES", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey INSTANCE_FINAL_FIELD_ATTRIBUTES = createTextAttributesKey("INSTANCE_FINAL_FIELD_ATTRIBUTES", INSTANCE_FIELD_ATTRIBUTES);
    public static final TextAttributesKey STATIC_FIELD_ATTRIBUTES = createTextAttributesKey("STATIC_FIELD_ATTRIBUTES", DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey STATIC_FIELD_IMPORTED_ATTRIBUTES = createTextAttributesKey("STATIC_FIELD_IMPORTED_ATTRIBUTES", STATIC_FIELD_ATTRIBUTES);
    public static final TextAttributesKey STATIC_FINAL_FIELD_ATTRIBUTES = createTextAttributesKey("STATIC_FINAL_FIELD_ATTRIBUTES", STATIC_FIELD_ATTRIBUTES);
    public static final TextAttributesKey STATIC_FINAL_FIELD_IMPORTED_ATTRIBUTES = createTextAttributesKey("STATIC_FINAL_FIELD_IMPORTED_ATTRIBUTES", STATIC_FINAL_FIELD_ATTRIBUTES);
    public static final TextAttributesKey CLASS_NAME_ATTRIBUTES = createTextAttributesKey("CLASS_NAME_ATTRIBUTES", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey ANONYMOUS_CLASS_NAME_ATTRIBUTES = createTextAttributesKey("ANONYMOUS_CLASS_NAME_ATTRIBUTES", CLASS_NAME_ATTRIBUTES);
    public static final TextAttributesKey IMPLICIT_ANONYMOUS_CLASS_PARAMETER_ATTRIBUTES = createTextAttributesKey("IMPLICIT_ANONYMOUS_CLASS_PARAMETER_ATTRIBUTES", CLASS_NAME_ATTRIBUTES);
    public static final TextAttributesKey TYPE_PARAMETER_NAME_ATTRIBUTES = createTextAttributesKey("TYPE_PARAMETER_NAME_ATTRIBUTES", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey INTERFACE_NAME_ATTRIBUTES = createTextAttributesKey("INTERFACE_NAME_ATTRIBUTES", DefaultLanguageHighlighterColors.INTERFACE_NAME);
    public static final TextAttributesKey ENUM_NAME_ATTRIBUTES = createTextAttributesKey("ENUM_NAME_ATTRIBUTES", CLASS_NAME_ATTRIBUTES);
    public static final TextAttributesKey ABSTRACT_CLASS_NAME_ATTRIBUTES = createTextAttributesKey("ABSTRACT_CLASS_NAME_ATTRIBUTES", CLASS_NAME_ATTRIBUTES);
    public static final TextAttributesKey METHOD_CALL_ATTRIBUTES = createTextAttributesKey("METHOD_CALL_ATTRIBUTES", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey METHOD_DECLARATION_ATTRIBUTES = createTextAttributesKey("METHOD_DECLARATION_ATTRIBUTES", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey STATIC_METHOD_ATTRIBUTES = createTextAttributesKey("STATIC_METHOD_ATTRIBUTES", DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey STATIC_METHOD_CALL_IMPORTED_ATTRIBUTES = createTextAttributesKey("STATIC_METHOD_IMPORTED_ATTRIBUTES", STATIC_METHOD_ATTRIBUTES);
    public static final TextAttributesKey ABSTRACT_METHOD_ATTRIBUTES = createTextAttributesKey("ABSTRACT_METHOD_ATTRIBUTES", METHOD_CALL_ATTRIBUTES);
    public static final TextAttributesKey INHERITED_METHOD_ATTRIBUTES = createTextAttributesKey("INHERITED_METHOD_ATTRIBUTES", METHOD_CALL_ATTRIBUTES);
    public static final TextAttributesKey CONSTRUCTOR_CALL_ATTRIBUTES = createTextAttributesKey("CONSTRUCTOR_CALL_ATTRIBUTES", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey CONSTRUCTOR_DECLARATION_ATTRIBUTES = createTextAttributesKey("CONSTRUCTOR_DECLARATION_ATTRIBUTES", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey ANNOTATION_NAME_ATTRIBUTES = createTextAttributesKey("ANNOTATION_NAME_ATTRIBUTES", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES = createTextAttributesKey("ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey ANNOTATION_ATTRIBUTE_VALUE_ATTRIBUTES = createTextAttributesKey("ANNOTATION_ATTRIBUTE_VALUE_ATTRIBUTES", DefaultLanguageHighlighterColors.METADATA);

    //visibility
    public static final TextAttributesKey PUBLIC_REFERENCE_ATTRIBUTES = createTextAttributesKey("PUBLIC_REFERENCE", (TextAttributesKey)null);
    public static final TextAttributesKey PROTECTED_REFERENCE_ATTRIBUTES = createTextAttributesKey("PROTECTED_REFERENCE", (TextAttributesKey)null);
    public static final TextAttributesKey PACKAGE_PRIVATE_REFERENCE_ATTRIBUTES = createTextAttributesKey("PACKAGE_PRIVATE_REFERENCE", (TextAttributesKey)null);
    public static final TextAttributesKey PRIVATE_REFERENCE_ATTRIBUTES = createTextAttributesKey("PRIVATE_REFERENCE", (TextAttributesKey)null);
}