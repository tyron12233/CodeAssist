package com.tyron.code.highlighter;

import static com.tyron.code.highlighter.attributes.TextAttributesKeyUtils.createTextAttributesKey;

import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.ColorKey;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;

/**
 * Base highlighter colors for multiple languages.
 */
public final class DefaultLanguageHighlighterColors {
  public static final TextAttributesKey TEMPLATE_LANGUAGE_COLOR = createTextAttributesKey("DEFAULT_TEMPLATE_LANGUAGE_COLOR", HighlighterColors.TEXT);
  public static final TextAttributesKey IDENTIFIER = createTextAttributesKey("DEFAULT_IDENTIFIER", HighlighterColors.TEXT);
  public static final TextAttributesKey NUMBER = createTextAttributesKey("DEFAULT_NUMBER");
  public static final TextAttributesKey KEYWORD = createTextAttributesKey("DEFAULT_KEYWORD");
  public static final TextAttributesKey STRING = createTextAttributesKey("DEFAULT_STRING");
  public static final TextAttributesKey BLOCK_COMMENT = createTextAttributesKey("DEFAULT_BLOCK_COMMENT");
  public static final TextAttributesKey LINE_COMMENT = createTextAttributesKey("DEFAULT_LINE_COMMENT");
  public static final TextAttributesKey DOC_COMMENT = createTextAttributesKey("DEFAULT_DOC_COMMENT");
  public static final TextAttributesKey OPERATION_SIGN = createTextAttributesKey("DEFAULT_OPERATION_SIGN");
  public static final TextAttributesKey BRACES = createTextAttributesKey("DEFAULT_BRACES");
  public static final TextAttributesKey DOT = createTextAttributesKey("DEFAULT_DOT");
  public static final TextAttributesKey SEMICOLON = createTextAttributesKey("DEFAULT_SEMICOLON");
  public static final TextAttributesKey COMMA = createTextAttributesKey("DEFAULT_COMMA");
  public static final TextAttributesKey PARENTHESES = createTextAttributesKey("DEFAULT_PARENTHS");
  public static final TextAttributesKey BRACKETS = createTextAttributesKey("DEFAULT_BRACKETS");

  public static final TextAttributesKey LABEL = createTextAttributesKey("DEFAULT_LABEL", IDENTIFIER);
  public static final TextAttributesKey CONSTANT = createTextAttributesKey("DEFAULT_CONSTANT", IDENTIFIER);
  public static final TextAttributesKey LOCAL_VARIABLE = createTextAttributesKey("DEFAULT_LOCAL_VARIABLE", IDENTIFIER);
  public static final TextAttributesKey REASSIGNED_LOCAL_VARIABLE = createTextAttributesKey("DEFAULT_REASSIGNED_LOCAL_VARIABLE", LOCAL_VARIABLE);
  public static final TextAttributesKey GLOBAL_VARIABLE = createTextAttributesKey("DEFAULT_GLOBAL_VARIABLE", IDENTIFIER);

  public static final TextAttributesKey FUNCTION_DECLARATION = createTextAttributesKey("DEFAULT_FUNCTION_DECLARATION", IDENTIFIER);
  public static final TextAttributesKey FUNCTION_CALL = createTextAttributesKey("DEFAULT_FUNCTION_CALL", IDENTIFIER);
  public static final TextAttributesKey PARAMETER = createTextAttributesKey("DEFAULT_PARAMETER", IDENTIFIER);
  public static final TextAttributesKey REASSIGNED_PARAMETER = createTextAttributesKey("DEFAULT_REASSIGNED_PARAMETER", PARAMETER);
  public static final TextAttributesKey CLASS_NAME = createTextAttributesKey("DEFAULT_CLASS_NAME", IDENTIFIER);
  public static final TextAttributesKey INTERFACE_NAME = createTextAttributesKey("DEFAULT_INTERFACE_NAME", IDENTIFIER);
  public static final TextAttributesKey CLASS_REFERENCE = createTextAttributesKey("DEFAULT_CLASS_REFERENCE", IDENTIFIER);
  public static final TextAttributesKey INSTANCE_METHOD = createTextAttributesKey("DEFAULT_INSTANCE_METHOD", FUNCTION_DECLARATION);
  public static final TextAttributesKey INSTANCE_FIELD = createTextAttributesKey("DEFAULT_INSTANCE_FIELD", IDENTIFIER);
  public static final TextAttributesKey STATIC_METHOD = createTextAttributesKey("DEFAULT_STATIC_METHOD", FUNCTION_DECLARATION);
  public static final TextAttributesKey STATIC_FIELD = createTextAttributesKey("DEFAULT_STATIC_FIELD", IDENTIFIER);

  public static final TextAttributesKey DOC_COMMENT_MARKUP = createTextAttributesKey("DEFAULT_DOC_MARKUP");
  public static final TextAttributesKey DOC_COMMENT_TAG = createTextAttributesKey("DEFAULT_DOC_COMMENT_TAG");
  public static final TextAttributesKey DOC_COMMENT_TAG_VALUE = createTextAttributesKey("DEFAULT_DOC_COMMENT_TAG_VALUE");
  public static final ColorKey DOC_COMMENT_GUIDE = ColorKey.createColorKey("DOC_COMMENT_GUIDE");
  public static final ColorKey DOC_COMMENT_LINK = ColorKey.createColorKey("DOC_COMMENT_LINK");
  public static final TextAttributesKey VALID_STRING_ESCAPE = createTextAttributesKey("DEFAULT_VALID_STRING_ESCAPE");
  public static final TextAttributesKey INVALID_STRING_ESCAPE = createTextAttributesKey("DEFAULT_INVALID_STRING_ESCAPE");

  public static final TextAttributesKey PREDEFINED_SYMBOL = createTextAttributesKey("DEFAULT_PREDEFINED_SYMBOL", IDENTIFIER);
  public static final TextAttributesKey HIGHLIGHTED_REFERENCE = createTextAttributesKey("DEFAULT_HIGHLIGHTED_REFERENCE", STRING);

  public static final TextAttributesKey METADATA = createTextAttributesKey("DEFAULT_METADATA", HighlighterColors.TEXT);

  public static final TextAttributesKey MARKUP_TAG = createTextAttributesKey("DEFAULT_TAG", HighlighterColors.TEXT);
  public static final TextAttributesKey MARKUP_ATTRIBUTE = createTextAttributesKey("DEFAULT_ATTRIBUTE", IDENTIFIER);
  public static final TextAttributesKey MARKUP_ENTITY = createTextAttributesKey("DEFAULT_ENTITY", IDENTIFIER);
  public static final TextAttributesKey INLINE_PARAMETER_HINT = createTextAttributesKey("INLINE_PARAMETER_HINT");
  public static final TextAttributesKey INLINE_PARAMETER_HINT_HIGHLIGHTED = createTextAttributesKey("INLINE_PARAMETER_HINT_HIGHLIGHTED");
  public static final TextAttributesKey INLINE_PARAMETER_HINT_CURRENT = createTextAttributesKey("INLINE_PARAMETER_HINT_CURRENT");
  public static final TextAttributesKey INLAY_DEFAULT = createTextAttributesKey("INLAY_DEFAULT");
  public static final TextAttributesKey INLAY_TEXT_WITHOUT_BACKGROUND = createTextAttributesKey("INLAY_TEXT_WITHOUT_BACKGROUND");
  public static final ColorKey INLINE_REFACTORING_SETTINGS_DEFAULT = ColorKey.createColorKey("INLINE_REFACTORING_SETTINGS_DEFAULT");
  public static final ColorKey INLINE_REFACTORING_SETTINGS_FOCUSED = ColorKey.createColorKey("INLINE_REFACTORING_SETTINGS_FOCUSED");
  public static final ColorKey INLINE_REFACTORING_SETTINGS_HOVERED = ColorKey.createColorKey("INLINE_REFACTORING_SETTINGS_HOVERED");
}