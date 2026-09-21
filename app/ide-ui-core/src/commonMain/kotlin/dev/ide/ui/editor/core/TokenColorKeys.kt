package dev.ide.ui.editor.core

import dev.ide.ui.ext.EditorLanguageProfile
import dev.ide.ui.ext.SyntaxFamily
import dev.ide.ui.theme.colors.ColorKeys as K

/**
 * Which color-scheme attribute a lexical [TokenType] resolves to, per language.
 *
 * It takes the language because a token type does not mean the same thing everywhere: the shared scanners
 * emit a `TYPE` for a class name in a brace language and for a tag name in XML, a `PROPERTY` for a field
 * and for an XML attribute name. Mapping per [SyntaxFamily] is what gives XML and Markdown their own
 * user-editable colors with no change to the scanners themselves — they already distinguish the constructs,
 * and this is where that distinction is given a name a scheme can address.
 *
 * Lives beside the scanner rather than with the rest of the scheme model because [TokenType] is the
 * scanner's; the semantic half of the same question is `dev.ide.ui.theme.colors.semanticColorKey`, and both
 * are written against the same `ColorKeys` so the two highlighting layers cannot disagree.
 */
fun tokenColorKey(profile: EditorLanguageProfile, type: TokenType): String {
    profile.tokenColorKeys[type.name]?.let { return it }
    return when (profile.syntax) {
        SyntaxFamily.XML -> when (type) {
            TokenType.TYPE -> K.XML_TAG
            TokenType.PROPERTY -> K.XML_ATTRIBUTE
            TokenType.STRING -> K.XML_VALUE
            TokenType.COMMENT -> K.XML_COMMENT
            TokenType.TAG_DELIMITER -> K.XML_TAG_DELIMITER
            TokenType.NAMESPACE -> K.XML_NAMESPACE
            TokenType.ENTITY -> K.XML_ENTITY
            TokenType.PROLOG -> K.XML_PROLOG
            TokenType.CDATA -> K.XML_CDATA
            else -> genericTokenKey(type)
        }
        SyntaxFamily.MARKDOWN -> when (type) {
            TokenType.TYPE -> K.MD_HEADING
            TokenType.KEYWORD -> K.MD_LIST_MARKER
            TokenType.COMMENT -> K.MD_QUOTE
            TokenType.STRING -> K.MD_CODE
            TokenType.FUNC -> K.MD_LINK
            TokenType.PROPERTY -> K.MD_URL
            TokenType.PUNCT -> K.MD_RULE
            TokenType.EMPHASIS -> K.MD_EMPHASIS
            else -> genericTokenKey(type)
        }
        else -> genericTokenKey(type)
    }
}

/**
 * The language-neutral reading of a token type: what it means in a brace language, and the fallback for
 * every family that does not claim it.
 *
 * The markup and Markdown types resolve here too, to the nearest general thing, so a family that emits one
 * without claiming it is still colored rather than left at the default text color.
 */
private fun genericTokenKey(type: TokenType): String = when (type) {
    TokenType.KEYWORD -> K.KEYWORD
    TokenType.STRING -> K.STRING
    TokenType.COMMENT -> K.COMMENT
    TokenType.NUMBER -> K.NUMBER
    TokenType.ANNOTATION -> K.ANNOTATION
    TokenType.FUNC -> K.FUNCTION
    TokenType.TYPE -> K.TYPE
    TokenType.PUNCT -> K.PUNCTUATION
    TokenType.PROPERTY -> K.PROPERTY
    TokenType.KEYWORD_CONTROL -> K.KEYWORD_CONTROL
    TokenType.KEYWORD_MODIFIER -> K.KEYWORD_MODIFIER
    TokenType.DOC_COMMENT -> K.COMMENT_DOC
    TokenType.CHAR -> K.STRING_CHAR
    TokenType.RAW_STRING -> K.STRING_RAW
    TokenType.OPERATOR -> K.OPERATOR
    TokenType.BRACKET -> K.BRACKET
    TokenType.SEPARATOR -> K.SEPARATOR
    TokenType.TAG_DELIMITER -> K.PUNCTUATION
    TokenType.NAMESPACE -> K.NAMESPACE
    TokenType.ENTITY -> K.CONSTANT
    TokenType.PROLOG -> K.COMMENT
    TokenType.CDATA -> K.STRING
    TokenType.EMPHASIS -> K.TEXT
}
