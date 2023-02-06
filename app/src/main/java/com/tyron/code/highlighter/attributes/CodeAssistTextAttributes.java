package com.tyron.code.highlighter.attributes;

import android.graphics.Color;

import org.jetbrains.kotlin.com.intellij.openapi.editor.markup.EffectType;

public class CodeAssistTextAttributes {

    public static final CodeAssistTextAttributes DEFAULT = new CodeAssistTextAttributes(
            Color.TRANSPARENT,
            Color.BLACK,
            Color.RED,
            0,
            null
    );

    private final int backgroundColor;
    private final int foregroundColor;
    private final int errorStripeColor;
    private final int fontType;
    private final EffectType effectType;

    public CodeAssistTextAttributes(int backgroundColor,
                                    int foregroundColor,
                                    int errorStripeColor,
                                    int fontType,
                                    EffectType effectType) {
        this.backgroundColor = backgroundColor;
        this.foregroundColor = foregroundColor;
        this.errorStripeColor = errorStripeColor;
        this.fontType = fontType;
        this.effectType = effectType;
    }

    public EffectType getEffectType() {
        return effectType;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public int getForegroundColor() {
        return foregroundColor;
    }

    public int getErrorStripeColor() {
        return errorStripeColor;
    }

    public int getFontType() {
        return fontType;
    }
}
