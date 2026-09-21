package dev.ide.ui.theme.colors

import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.theme.colors.ColorKeys as K

/**
 * What a semantic token means in scheme terms: which attribute its kind resolves to, and which attributes
 * its modifiers layer over that.
 *
 * The lexical half of the same question lives beside the scanner, in `dev.ide.ui.editor.core.tokenColorKey`,
 * because it is answered per token type and the token types are the scanner's. The two halves have to agree
 * — the semantic pass overlays the lexical one, and a construct colored `function` by the lexer and
 * `kotlin.function.suspend` by the analyzer has to read as the same thing getting more specific, not as two
 * unrelated colors — so both are written against the same [ColorKeys].
 */

/**
 * The attribute a semantic token [kind] colors to, or null for a kind nothing has an attribute for.
 *
 * The kind set is OPEN — the highlight SPI lets a backend invent one — so a kind the shell does not know
 * is looked up in the registry under its own name before giving up. That is what lets a language colour a
 * construct the shared scanners cannot even see: register `mylang.directive` as an attribute and emit
 * `mylang.directive` as a highlight kind, and the two meet here with nothing in between to teach.
 *
 * It matters because the lexical half can only redirect token types the scanner already distinguishes. A
 * C preprocessor directive arrives as a `KEYWORD` like every other keyword, so no token mapping can
 * separate them; the semantic pass can, because it knows what it resolved.
 *
 * Null is the honest answer when neither half knows the kind: the token is dropped and the lexer's coloring
 * shows through unchanged, which is always defensible because the lexer already ran.
 */
fun semanticColorKey(kind: String): String? = when (kind) {
    "class", "interface", "enum", "object" -> K.TYPE
    "annotation" -> K.ANNOTATION
    "typeParameter" -> K.TYPE_PARAMETER
    "method", "function", "constructor" -> K.FUNCTION
    "property", "field" -> K.PROPERTY
    "enumConstant", "constant" -> K.CONSTANT
    "parameter" -> K.VARIABLE_PARAMETER
    "localVariable" -> K.VARIABLE
    "label" -> K.LABEL
    "keyword" -> K.KEYWORD
    "stringTemplateEntry" -> K.STRING_TEMPLATE
    "stringEscape" -> K.STRING_ESCAPE
    "namespace" -> K.NAMESPACE
    "xmlNamespace" -> K.XML_NAMESPACE
    "xmlReference" -> K.XML_REFERENCE
    // A kind the shell does not ship is its own attribute key, if something registered one.
    else -> kind.takeIf { ColorAttributes.byKey(it) != null }
}

/**
 * The attributes layered over [baseKey] for a token's [mods], least specific first.
 *
 * Order is the precedence the editor has always had: a `suspend` extension reads as suspend, a mutable
 * receiver as mutable, and `static`/`deprecated` only ever add a font style on top of whatever won. Each is
 * a real attribute, so "make deprecated symbols grey instead of struck through" is a scheme edit.
 */
fun modifierColorKeys(baseKey: String, mods: Set<UiHighlightModifier>): List<String> {
    if (mods.isEmpty()) return emptyList()
    val keys = ArrayList<String>(3)
    if (UiHighlightModifier.Declaration in mods && baseKey == K.FUNCTION) keys.add(K.FUNCTION_DECLARATION)
    if (UiHighlightModifier.Extension in mods) keys.add(K.KOTLIN_EXTENSION)
    if (UiHighlightModifier.Composable in mods) keys.add(K.KOTLIN_COMPOSABLE)
    if (UiHighlightModifier.Suspend in mods) keys.add(K.KOTLIN_SUSPEND)
    if (UiHighlightModifier.Mutable in mods) keys.add(K.KOTLIN_MUTABLE)
    if (UiHighlightModifier.Static in mods) keys.add(K.MODIFIER_STATIC)
    if (UiHighlightModifier.Deprecated in mods) keys.add(K.MODIFIER_DEPRECATED)
    return keys
}

/**
 * The resolved style for a semantic token: its base attribute with every modifier attribute layered over
 * it, or null when the kind has no attribute (the lexical layer keeps the run).
 */
fun ResolvedColorScheme.semanticStyle(kind: String, mods: Set<UiHighlightModifier>): AttributeStyle? {
    val baseKey = semanticColorKey(kind) ?: return null
    var style = styleOf(baseKey)
    for (key in modifierColorKeys(baseKey, mods)) style = styleOf(key).mergedOnto(style)
    return style
}
