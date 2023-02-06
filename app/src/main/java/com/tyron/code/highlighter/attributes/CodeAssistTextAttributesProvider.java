package com.tyron.code.highlighter.attributes;

import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;

public abstract class CodeAssistTextAttributesProvider {
    public abstract CodeAssistTextAttributes getDefaultAttributes(TextAttributesKey textAttributesKey);
}
