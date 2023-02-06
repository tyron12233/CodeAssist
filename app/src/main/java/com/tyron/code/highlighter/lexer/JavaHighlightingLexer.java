package com.tyron.code.highlighter.lexer;

import org.jetbrains.kotlin.com.intellij.lang.java.JavaParserDefinition;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaDocElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

public class JavaHighlightingLexer extends LayeredLexer {

    public JavaHighlightingLexer(LanguageLevel languageLevel) {
        super(JavaParserDefinition.createLexer(languageLevel));

        registerSelfStoppingLayer(new JavaStringLiteralLexer('\"', JavaTokenType.STRING_LITERAL, false, "s"),
                new IElementType[]{JavaTokenType.STRING_LITERAL}, IElementType.EMPTY_ARRAY);

        registerSelfStoppingLayer(new JavaStringLiteralLexer('\'', JavaTokenType.STRING_LITERAL),
                new IElementType[]{JavaTokenType.CHARACTER_LITERAL}, IElementType.EMPTY_ARRAY);

        registerSelfStoppingLayer(new JavaStringLiteralLexer(StringLiteralLexer.NO_QUOTE_CHAR, JavaTokenType.TEXT_BLOCK_LITERAL, true, "s"),
                new IElementType[]{JavaTokenType.TEXT_BLOCK_LITERAL}, IElementType.EMPTY_ARRAY);

        LayeredLexer docLexer = new LayeredLexer(JavaParserDefinition.createDocLexer(languageLevel));
//        HtmlHighlightingLexer htmlLexer = new HtmlHighlightingLexer(null);
//        htmlLexer.setHasNoEmbeddments(true);
//        docLexer.registerLayer(htmlLexer, JavaDocTokenType.DOC_COMMENT_DATA);
        registerSelfStoppingLayer(docLexer, new IElementType[]{JavaDocElementType.DOC_COMMENT}, IElementType.EMPTY_ARRAY);
    }
}
