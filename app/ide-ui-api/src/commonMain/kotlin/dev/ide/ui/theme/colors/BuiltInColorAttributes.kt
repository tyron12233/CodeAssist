package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color

/** The attribute keys the shell ships. Constants rather than literals so a rename is a compile error. */
object ColorKeys {
    // ---- editor surface + decorations (theme-derived by default; see SchemeDefaults) ----
    const val EDITOR_BACKGROUND = "editor.background"
    const val EDITOR_CARET = "editor.caret"
    const val EDITOR_SELECTION = "editor.selection"
    const val EDITOR_CURRENT_LINE = "editor.currentLine"
    const val EDITOR_INDENT_GUIDE = "editor.indentGuide"
    const val EDITOR_FIND_MATCH = "editor.findMatch"
    const val EDITOR_FIND_CURRENT = "editor.findCurrent"
    const val EDITOR_OCCURRENCE = "editor.occurrence"
    const val EDITOR_TEMPLATE_FIELD = "editor.templateField"
    const val EDITOR_COMPOSING = "editor.composing"
    const val EDITOR_INLAY_HINT = "editor.inlayHint"
    const val EDITOR_FOLD_PLACEHOLDER = "editor.foldPlaceholder"

    const val GUTTER_TEXT = "gutter.text"
    const val GUTTER_CURRENT = "gutter.current"
    const val GUTTER_BORDER = "gutter.border"

    const val DIAGNOSTIC_ERROR = "diagnostic.error"
    const val DIAGNOSTIC_WARNING = "diagnostic.warning"
    const val DIAGNOSTIC_INFO = "diagnostic.info"

    // ---- language-neutral code ----
    /** The root: every non-overlay attribute falls back here, so a scheme is never short of a color. */
    const val TEXT = "text"
    const val KEYWORD = "keyword"
    const val KEYWORD_MODIFIER = "keyword.modifier"
    const val STRING = "string"
    const val STRING_ESCAPE = "string.escape"
    const val STRING_TEMPLATE = "string.template"
    const val NUMBER = "number"
    const val COMMENT = "comment"
    const val ANNOTATION = "annotation"
    const val FUNCTION = "function"
    const val FUNCTION_DECLARATION = "function.declaration"
    const val TYPE = "type"
    const val TYPE_PARAMETER = "type.parameter"
    const val PUNCTUATION = "punctuation"
    const val PROPERTY = "property"
    const val VARIABLE = "variable"
    const val VARIABLE_PARAMETER = "variable.parameter"
    const val CONSTANT = "constant"
    const val LABEL = "label"
    const val NAMESPACE = "namespace"

    // ---- finer lexical classes; each falls back to the coarse one above ----
    const val KEYWORD_CONTROL = "keyword.control"
    const val COMMENT_DOC = "comment.doc"
    const val STRING_CHAR = "string.char"
    const val STRING_RAW = "string.raw"
    const val OPERATOR = "punctuation.operator"
    const val BRACKET = "punctuation.bracket"
    const val SEPARATOR = "punctuation.separator"

    // ---- finer semantic classes; the analyzer already told these apart ----
    const val TYPE_CLASS = "type.class"
    const val TYPE_INTERFACE = "type.interface"
    const val TYPE_ENUM = "type.enum"
    const val FUNCTION_MEMBER = "function.member"
    const val FUNCTION_TOP_LEVEL = "function.topLevel"
    const val FUNCTION_CONSTRUCTOR = "function.constructor"
    const val PROPERTY_FIELD = "property.field"
    const val CONSTANT_ENUM = "constant.enum"

    // ---- overlays: added to whatever the symbol already is ----
    const val MODIFIER_STATIC = "modifier.static"
    const val MODIFIER_DEPRECATED = "modifier.deprecated"

    // ---- Kotlin ----
    const val KOTLIN_OBJECT = "kotlin.type.object"
    const val KOTLIN_EXTENSION = "kotlin.function.extension"
    const val KOTLIN_COMPOSABLE = "kotlin.function.composable"
    const val KOTLIN_SUSPEND = "kotlin.function.suspend"
    const val KOTLIN_MUTABLE = "kotlin.variable.mutable"

    // ---- XML ----
    const val XML_TAG = "xml.tag"
    const val XML_ATTRIBUTE = "xml.attribute"
    const val XML_VALUE = "xml.value"
    const val XML_COMMENT = "xml.comment"
    const val XML_TAG_DELIMITER = "xml.tagDelimiter"
    const val XML_ENTITY = "xml.entity"
    const val XML_PROLOG = "xml.prolog"
    const val XML_CDATA = "xml.cdata"
    const val XML_NAMESPACE = "xml.namespace"
    const val XML_REFERENCE = "xml.reference"

    // ---- Markdown ----
    const val MD_HEADING = "markdown.heading"
    const val MD_LIST_MARKER = "markdown.listMarker"
    const val MD_QUOTE = "markdown.quote"
    const val MD_CODE = "markdown.code"
    const val MD_LINK = "markdown.link"
    const val MD_URL = "markdown.url"
    const val MD_RULE = "markdown.rule"
    const val MD_EMPHASIS = "markdown.emphasis"
}

/** Group ids, in the order the color scheme editor shows them. */
object ColorGroups {
    const val CODE = "code"
    const val MODIFIERS = "modifiers"
    const val KOTLIN = "kotlin"
    const val XML = "xml"
    const val MARKDOWN = "markdown"
    const val GENERAL = "general"
    const val GUTTER = "gutter"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * The shipped attributes and their defaults — the values the editor was hardcoded to before schemes
 * existed, so a fresh install renders byte-identically to the previous build and a scheme that overrides
 * nothing is exactly the old theme.
 *
 * The chrome attributes (the [ColorGroups.GENERAL], [ColorGroups.GUTTER] and [ColorGroups.DIAGNOSTICS]
 * groups) deliberately carry NO defaults: their unstyled value comes from the live Material theme through
 * [SchemeDefaults], so the caret and selection keep tracking the accent (including Material You) until a
 * scheme says otherwise. Everything a scheme does not pin still follows the user's accent.
 */
object BuiltInColorAttributes {
    val groups = listOf(
        ColorGroup(ColorGroups.CODE, "Code", 0),
        ColorGroup(ColorGroups.MODIFIERS, "Modifiers", 10),
        ColorGroup(ColorGroups.KOTLIN, "Kotlin", 20),
        ColorGroup(ColorGroups.XML, "XML", 30),
        ColorGroup(ColorGroups.MARKDOWN, "Markdown", 40),
        ColorGroup(ColorGroups.GENERAL, "Editor", 50),
        ColorGroup(ColorGroups.GUTTER, "Gutter", 60),
        ColorGroup(ColorGroups.DIAGNOSTICS, "Diagnostics", 70),
    )

    private fun code(
        key: String, title: String, order: Int,
        parent: String? = ColorKeys.TEXT,
        dark: AttributeStyle = AttributeStyle.EMPTY,
        light: AttributeStyle = AttributeStyle.EMPTY,
    ) = ColorAttribute(key, title, ColorGroups.CODE, parent, dark, light, order = order)

    private fun chrome(
        key: String, title: String, group: String, order: Int,
        bg: Boolean = false, fontStyled: Boolean = false,
    ) = ColorAttribute(
        key, title, group, parent = null,
        backgroundOnly = bg, foregroundOnly = !bg, fontStyled = fontStyled, order = order,
    )

    val attributes: List<ColorAttribute> = buildList {
        // ---- Code -----------------------------------------------------------------------------------
        add(
            ColorAttribute(
                ColorKeys.TEXT, "Default text", ColorGroups.CODE, parent = null,
                defaultDark = AttributeStyle.fg(Color(0xFFC7C8CF)),
                defaultLight = AttributeStyle.fg(Color(0xFF34363D)),
                order = 0,
            )
        )
        add(code(ColorKeys.KEYWORD, "Keyword", 1, dark = AttributeStyle.fg(Color(0xFFCD7EE0)), light = AttributeStyle.fg(Color(0xFFA32FB0))))
        add(code(ColorKeys.KEYWORD_MODIFIER, "Modifier keyword", 2, parent = ColorKeys.KEYWORD))
        add(code(ColorKeys.STRING, "String", 3, dark = AttributeStyle.fg(Color(0xFF98C97A)), light = AttributeStyle.fg(Color(0xFF3F9C45))))
        add(code(ColorKeys.STRING_ESCAPE, "Escape sequence", 4, parent = ColorKeys.STRING, dark = AttributeStyle.fg(Color(0xFFD9A066)), light = AttributeStyle.fg(Color(0xFFB9690B))))
        add(code(ColorKeys.STRING_TEMPLATE, "Template delimiter", 5, parent = ColorKeys.STRING, dark = AttributeStyle.fg(Color(0xFFCD7EE0)), light = AttributeStyle.fg(Color(0xFFA32FB0))))
        add(code(ColorKeys.NUMBER, "Number", 6, dark = AttributeStyle.fg(Color(0xFFD9A066)), light = AttributeStyle.fg(Color(0xFFB9690B))))
        add(
            code(
                ColorKeys.COMMENT, "Comment", 7,
                dark = AttributeStyle(foreground = Color(0xFF6C7078), italic = true),
                light = AttributeStyle(foreground = Color(0xFFA3A4AA), italic = true),
            )
        )
        add(code(ColorKeys.ANNOTATION, "Annotation", 8, dark = AttributeStyle.fg(Color(0xFFE6C178)), light = AttributeStyle.fg(Color(0xFF9A6700))))
        add(code(ColorKeys.FUNCTION, "Function call", 9, dark = AttributeStyle.fg(Color(0xFF61AFEF)), light = AttributeStyle.fg(Color(0xFF3A6FE0))))
        add(code(ColorKeys.FUNCTION_DECLARATION, "Function declaration", 10, parent = ColorKeys.FUNCTION, dark = AttributeStyle(bold = true), light = AttributeStyle(bold = true)))
        add(code(ColorKeys.TYPE, "Type", 11, dark = AttributeStyle.fg(Color(0xFFE6C178)), light = AttributeStyle.fg(Color(0xFF9A6700))))
        add(code(ColorKeys.TYPE_PARAMETER, "Type parameter", 12, parent = ColorKeys.TYPE))
        add(code(ColorKeys.PROPERTY, "Property", 13, dark = AttributeStyle.fg(Color(0xFF57B6C2)), light = AttributeStyle.fg(Color(0xFF0A86A8))))
        add(code(ColorKeys.VARIABLE, "Local variable", 14, dark = AttributeStyle.fg(Color(0xFFE08C84)), light = AttributeStyle.fg(Color(0xFFC0473F))))
        add(code(ColorKeys.VARIABLE_PARAMETER, "Parameter", 15, parent = ColorKeys.VARIABLE))
        add(code(ColorKeys.CONSTANT, "Constant", 16, dark = AttributeStyle.fg(Color(0xFFD9A066)), light = AttributeStyle.fg(Color(0xFFB9690B))))
        add(
            code(
                ColorKeys.LABEL, "Label", 17,
                dark = AttributeStyle(foreground = Color(0xFFB5895B), italic = true),
                light = AttributeStyle(foreground = Color(0xFF8A5A2B), italic = true),
            )
        )
        add(code(ColorKeys.NAMESPACE, "Package / namespace", 18))
        add(code(ColorKeys.PUNCTUATION, "Punctuation", 19, dark = AttributeStyle.fg(Color(0xFF8B8D96)), light = AttributeStyle.fg(Color(0xFF6B6C73))))

        // The finer classes below ship with NO color of their own, on purpose: each renders exactly as its
        // parent until a scheme separates them, so adding them changes nothing about how anyone's editor
        // looks and everything about what they can change. The shipped presets DO separate several.
        add(code(ColorKeys.KEYWORD_CONTROL, "Control keyword", 20, parent = ColorKeys.KEYWORD))
        add(code(ColorKeys.COMMENT_DOC, "Doc comment", 21, parent = ColorKeys.COMMENT))
        add(code(ColorKeys.STRING_CHAR, "Character literal", 22, parent = ColorKeys.STRING))
        add(code(ColorKeys.STRING_RAW, "Raw string", 23, parent = ColorKeys.STRING))
        add(code(ColorKeys.OPERATOR, "Operator", 24, parent = ColorKeys.PUNCTUATION))
        add(code(ColorKeys.BRACKET, "Braces and brackets", 25, parent = ColorKeys.PUNCTUATION))
        add(code(ColorKeys.SEPARATOR, "Semicolon, comma, dot", 26, parent = ColorKeys.PUNCTUATION))
        add(code(ColorKeys.TYPE_CLASS, "Class", 27, parent = ColorKeys.TYPE))
        add(code(ColorKeys.TYPE_INTERFACE, "Interface", 28, parent = ColorKeys.TYPE))
        add(code(ColorKeys.TYPE_ENUM, "Enum", 29, parent = ColorKeys.TYPE))
        add(code(ColorKeys.FUNCTION_MEMBER, "Member function", 30, parent = ColorKeys.FUNCTION))
        add(code(ColorKeys.FUNCTION_TOP_LEVEL, "Top-level function", 31, parent = ColorKeys.FUNCTION))
        add(code(ColorKeys.FUNCTION_CONSTRUCTOR, "Constructor", 32, parent = ColorKeys.FUNCTION))
        add(code(ColorKeys.PROPERTY_FIELD, "Field", 33, parent = ColorKeys.PROPERTY))
        add(code(ColorKeys.CONSTANT_ENUM, "Enum constant", 34, parent = ColorKeys.CONSTANT))

        // ---- Modifiers (overlays) -------------------------------------------------------------------
        add(
            ColorAttribute(
                ColorKeys.MODIFIER_STATIC, "Static member", ColorGroups.MODIFIERS, parent = null,
                defaultDark = AttributeStyle(italic = true), defaultLight = AttributeStyle(italic = true),
                overlay = true, order = 0,
            )
        )
        add(
            ColorAttribute(
                ColorKeys.MODIFIER_DEPRECATED, "Deprecated symbol", ColorGroups.MODIFIERS, parent = null,
                defaultDark = AttributeStyle(strikethrough = true), defaultLight = AttributeStyle(strikethrough = true),
                overlay = true, order = 10,
            )
        )

        // ---- Kotlin -----------------------------------------------------------------------------------
        add(ColorAttribute(ColorKeys.KOTLIN_OBJECT, "Object declaration", ColorGroups.KOTLIN, ColorKeys.TYPE, order = -10))
        add(
            ColorAttribute(
                ColorKeys.KOTLIN_EXTENSION, "Extension function", ColorGroups.KOTLIN, ColorKeys.FUNCTION,
                defaultDark = AttributeStyle(foreground = Color(0xFF82AAFF), italic = true),
                defaultLight = AttributeStyle(foreground = Color(0xFF3A6FB5), italic = true),
                order = 0,
            )
        )
        add(
            ColorAttribute(
                ColorKeys.KOTLIN_COMPOSABLE, "@Composable function", ColorGroups.KOTLIN, ColorKeys.FUNCTION,
                defaultDark = AttributeStyle(foreground = Color(0xFF4FC1A6), bold = true),
                defaultLight = AttributeStyle(foreground = Color(0xFF1F8A77), bold = true),
                order = 10,
            )
        )
        add(
            ColorAttribute(
                ColorKeys.KOTLIN_SUSPEND, "Suspend function", ColorGroups.KOTLIN, ColorKeys.FUNCTION,
                defaultDark = AttributeStyle(foreground = Color(0xFFD9A0C9), italic = true),
                defaultLight = AttributeStyle(foreground = Color(0xFF9C4E8A), italic = true),
                order = 20,
            )
        )
        add(
            ColorAttribute(
                ColorKeys.KOTLIN_MUTABLE, "Mutable variable (var)", ColorGroups.KOTLIN, ColorKeys.VARIABLE,
                defaultDark = AttributeStyle(foreground = Color(0xFFE0918A), underline = true),
                defaultLight = AttributeStyle(foreground = Color(0xFFC0473F), underline = true),
                order = 30,
            )
        )

        // ---- XML --------------------------------------------------------------------------------------
        add(ColorAttribute(ColorKeys.XML_TAG, "Tag name", ColorGroups.XML, ColorKeys.TYPE, order = 0))
        add(ColorAttribute(ColorKeys.XML_ATTRIBUTE, "Attribute name", ColorGroups.XML, ColorKeys.PROPERTY, order = 10))
        add(ColorAttribute(ColorKeys.XML_VALUE, "Attribute value", ColorGroups.XML, ColorKeys.STRING, order = 20))
        add(ColorAttribute(ColorKeys.XML_NAMESPACE, "Namespace prefix", ColorGroups.XML, ColorKeys.KEYWORD, order = 30))
        add(ColorAttribute(ColorKeys.XML_REFERENCE, "Resource reference", ColorGroups.XML, ColorKeys.CONSTANT, order = 40))
        add(ColorAttribute(ColorKeys.XML_COMMENT, "Comment", ColorGroups.XML, ColorKeys.COMMENT, order = 50))
        add(ColorAttribute(ColorKeys.XML_TAG_DELIMITER, "Angle brackets and colon", ColorGroups.XML, ColorKeys.PUNCTUATION, order = 60))
        add(ColorAttribute(ColorKeys.XML_ENTITY, "Entity reference", ColorGroups.XML, ColorKeys.CONSTANT, order = 70))
        add(ColorAttribute(ColorKeys.XML_PROLOG, "Prolog and DOCTYPE", ColorGroups.XML, ColorKeys.COMMENT, order = 80))
        add(ColorAttribute(ColorKeys.XML_CDATA, "CDATA section", ColorGroups.XML, ColorKeys.STRING, order = 90))

        // ---- Markdown ---------------------------------------------------------------------------------
        add(ColorAttribute(ColorKeys.MD_HEADING, "Heading", ColorGroups.MARKDOWN, ColorKeys.TYPE, order = 0))
        add(ColorAttribute(ColorKeys.MD_LIST_MARKER, "List marker", ColorGroups.MARKDOWN, ColorKeys.KEYWORD, order = 10))
        add(ColorAttribute(ColorKeys.MD_QUOTE, "Block quote", ColorGroups.MARKDOWN, ColorKeys.COMMENT, order = 20))
        add(ColorAttribute(ColorKeys.MD_CODE, "Code span / fence", ColorGroups.MARKDOWN, ColorKeys.STRING, order = 30))
        add(ColorAttribute(ColorKeys.MD_LINK, "Link text", ColorGroups.MARKDOWN, ColorKeys.FUNCTION, order = 40))
        add(ColorAttribute(ColorKeys.MD_URL, "Link target", ColorGroups.MARKDOWN, ColorKeys.PROPERTY, order = 50))
        add(ColorAttribute(ColorKeys.MD_RULE, "Thematic break", ColorGroups.MARKDOWN, ColorKeys.PUNCTUATION, order = 60))
        add(ColorAttribute(ColorKeys.MD_EMPHASIS, "Bold and italic", ColorGroups.MARKDOWN, ColorKeys.TEXT, order = 70))

        // ---- Editor chrome ------------------------------------------------------------------------------
        add(chrome(ColorKeys.EDITOR_BACKGROUND, "Background", ColorGroups.GENERAL, 0, bg = true))
        add(chrome(ColorKeys.EDITOR_CARET, "Caret", ColorGroups.GENERAL, 10))
        add(chrome(ColorKeys.EDITOR_SELECTION, "Selection", ColorGroups.GENERAL, 20, bg = true))
        add(chrome(ColorKeys.EDITOR_CURRENT_LINE, "Current line", ColorGroups.GENERAL, 30, bg = true))
        add(chrome(ColorKeys.EDITOR_INDENT_GUIDE, "Indent guide", ColorGroups.GENERAL, 40))
        add(chrome(ColorKeys.EDITOR_FIND_MATCH, "Search result", ColorGroups.GENERAL, 50, bg = true))
        add(chrome(ColorKeys.EDITOR_FIND_CURRENT, "Current search result", ColorGroups.GENERAL, 60, bg = true))
        add(chrome(ColorKeys.EDITOR_OCCURRENCE, "Identifier under caret", ColorGroups.GENERAL, 70, bg = true))
        add(chrome(ColorKeys.EDITOR_TEMPLATE_FIELD, "Template field", ColorGroups.GENERAL, 80, bg = true))
        add(chrome(ColorKeys.EDITOR_COMPOSING, "Composing text", ColorGroups.GENERAL, 90, fontStyled = true))
        add(
            ColorAttribute(
                ColorKeys.EDITOR_INLAY_HINT, "Inlay hint", ColorGroups.GENERAL, parent = null,
                defaultDark = AttributeStyle(italic = true), defaultLight = AttributeStyle(italic = true),
                order = 100,
            )
        )
        add(ColorAttribute(ColorKeys.EDITOR_FOLD_PLACEHOLDER, "Folded region", ColorGroups.GENERAL, parent = null, order = 110))

        add(chrome(ColorKeys.GUTTER_TEXT, "Line number", ColorGroups.GUTTER, 0, fontStyled = true))
        add(chrome(ColorKeys.GUTTER_CURRENT, "Current line number", ColorGroups.GUTTER, 10, fontStyled = true))
        add(chrome(ColorKeys.GUTTER_BORDER, "Gutter border", ColorGroups.GUTTER, 20))

        add(chrome(ColorKeys.DIAGNOSTIC_ERROR, "Error", ColorGroups.DIAGNOSTICS, 0))
        add(chrome(ColorKeys.DIAGNOSTIC_WARNING, "Warning", ColorGroups.DIAGNOSTICS, 10))
        add(chrome(ColorKeys.DIAGNOSTIC_INFO, "Information", ColorGroups.DIAGNOSTICS, 20))
    }
}
