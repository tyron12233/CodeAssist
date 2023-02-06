package com.tyron.code.highlighter;

import androidx.annotation.NonNull;

import com.tyron.code.highlighter.lexer.JavaHighlightingLexer;

import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;
import org.jetbrains.kotlin.com.intellij.psi.JavaDocTokenType;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.StringEscapesTokenTypes;
import org.jetbrains.kotlin.com.intellij.psi.TokenType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.ElementType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaDocElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

import java.util.HashMap;
import java.util.Map;

public class JavaFileHighlighter extends SyntaxHighlighterBase {

    private static final Map<IElementType, TextAttributesKey> ourMap1;
    private static final Map<IElementType, TextAttributesKey> ourMap2;

    static {
        ourMap1 = new HashMap<>();
        ourMap2 = new HashMap<>();

        fillMap(ourMap1, ElementType.KEYWORD_BIT_SET, JavaHighlightingColors.KEYWORD);
        fillMap(ourMap1, ElementType.LITERAL_BIT_SET, JavaHighlightingColors.KEYWORD);
        fillMap(ourMap1, ElementType.OPERATION_BIT_SET, JavaHighlightingColors.OPERATION_SIGN);

        for (IElementType type : JavaDocTokenType.ALL_JAVADOC_TOKENS.getTypes()) {
            ourMap1.put(type, JavaHighlightingColors.DOC_COMMENT);
        }

//        ourMap1.put(XmlTokenType.XML_DATA_CHARACTERS, JavaHighlightingColors.DOC_COMMENT);
//        ourMap1.put(XmlTokenType.XML_REAL_WHITE_SPACE, JavaHighlightingColors.DOC_COMMENT);
//        ourMap1.put(XmlTokenType.TAG_WHITE_SPACE, JavaHighlightingColors.DOC_COMMENT);

        ourMap1.put(JavaTokenType.INTEGER_LITERAL, JavaHighlightingColors.NUMBER);
        ourMap1.put(JavaTokenType.LONG_LITERAL, JavaHighlightingColors.NUMBER);
        ourMap1.put(JavaTokenType.FLOAT_LITERAL, JavaHighlightingColors.NUMBER);
        ourMap1.put(JavaTokenType.DOUBLE_LITERAL, JavaHighlightingColors.NUMBER);
        ourMap1.put(JavaTokenType.STRING_LITERAL, JavaHighlightingColors.STRING);
        ourMap1.put(JavaTokenType.TEXT_BLOCK_LITERAL, JavaHighlightingColors.STRING);
        ourMap1.put(StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN, JavaHighlightingColors.VALID_STRING_ESCAPE);
        ourMap1.put(StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN, JavaHighlightingColors.INVALID_STRING_ESCAPE);
        ourMap1.put(StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN, JavaHighlightingColors.INVALID_STRING_ESCAPE);
        ourMap1.put(JavaTokenType.CHARACTER_LITERAL, JavaHighlightingColors.STRING);

        ourMap1.put(JavaTokenType.LPARENTH, JavaHighlightingColors.PARENTHESES);
        ourMap1.put(JavaTokenType.RPARENTH, JavaHighlightingColors.PARENTHESES);

        ourMap1.put(JavaTokenType.LBRACE, JavaHighlightingColors.BRACES);
        ourMap1.put(JavaTokenType.RBRACE, JavaHighlightingColors.BRACES);

        ourMap1.put(JavaTokenType.LBRACKET, JavaHighlightingColors.BRACKETS);
        ourMap1.put(JavaTokenType.RBRACKET, JavaHighlightingColors.BRACKETS);

        ourMap1.put(JavaTokenType.COMMA, JavaHighlightingColors.COMMA);
        ourMap1.put(JavaTokenType.DOT, JavaHighlightingColors.DOT);
        ourMap1.put(JavaTokenType.SEMICOLON, JavaHighlightingColors.JAVA_SEMICOLON);

        ourMap1.put(JavaTokenType.C_STYLE_COMMENT, JavaHighlightingColors.JAVA_BLOCK_COMMENT);
        ourMap1.put(JavaDocElementType.DOC_COMMENT, JavaHighlightingColors.DOC_COMMENT);
        ourMap1.put(JavaTokenType.END_OF_LINE_COMMENT, JavaHighlightingColors.LINE_COMMENT);
        ourMap1.put(TokenType.BAD_CHARACTER, HighlighterColors.BAD_CHARACTER);

        ourMap1.put(JavaDocTokenType.DOC_TAG_NAME, JavaHighlightingColors.DOC_COMMENT);
        ourMap2.put(JavaDocTokenType.DOC_TAG_NAME, JavaHighlightingColors.DOC_COMMENT_TAG);

//        IElementType[] javaDocMarkup = {
//                XmlTokenType.XML_START_TAG_START, XmlTokenType.XML_END_TAG_START, XmlTokenType.XML_TAG_END, XmlTokenType.XML_EMPTY_ELEMENT_END,
//                XmlTokenType.TAG_WHITE_SPACE, XmlTokenType.XML_TAG_NAME, XmlTokenType.XML_NAME, XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN,
//                XmlTokenType.XML_ATTRIBUTE_VALUE_START_DELIMITER, XmlTokenType.XML_ATTRIBUTE_VALUE_END_DELIMITER, XmlTokenType.XML_CHAR_ENTITY_REF,
//                XmlTokenType.XML_ENTITY_REF_TOKEN, XmlTokenType.XML_EQ
//        };
    }

    protected final LanguageLevel myLanguageLevel;

    public JavaFileHighlighter() {
        this(LanguageLevel.HIGHEST);
    }

    public JavaFileHighlighter(@NonNull LanguageLevel languageLevel) {
        myLanguageLevel = languageLevel;
    }

    @Override
    @NonNull
    public Lexer getHighlightingLexer() {
        return new JavaHighlightingLexer(myLanguageLevel);
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        return pack(ourMap1.get(tokenType), ourMap2.get(tokenType));
    }
}
