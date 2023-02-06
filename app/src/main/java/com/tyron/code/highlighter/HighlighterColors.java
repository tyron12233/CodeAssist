package com.tyron.code.highlighter;

import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;

public interface HighlighterColors {
  TextAttributesKey NO_HIGHLIGHTING = TextAttributesKey.createTextAttributesKey("DEFAULT");
  TextAttributesKey TEXT = TextAttributesKey.createTextAttributesKey("TEXT");
  TextAttributesKey BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("BAD_CHARACTER");
}