package dev.codeassist.csharp.completion

/** C#'s keywords, as the editor colors them and as completion offers them. */
internal object CsKeywords {
    /** C#'s reserved words and the contextual ones that read as keywords in ordinary code. */
    val ALL = setOf(
        "abstract", "as", "async", "await", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event",
        "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "get", "goto", "if",
        "implicit", "in", "init", "int", "interface", "internal", "is", "lock", "long", "namespace", "new",
        "null", "object", "operator", "out", "override", "params", "partial", "private", "protected", "public",
        "readonly", "record", "ref", "required", "return", "sbyte", "sealed", "set", "short", "sizeof",
        "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint",
        "ulong", "unchecked", "unsafe", "ushort", "using", "var", "virtual", "void", "volatile", "when",
        "where", "while", "yield",
    )
}
