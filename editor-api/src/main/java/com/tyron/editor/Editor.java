package com.tyron.editor;


import com.tyron.legacyEditor.Caret;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolder;

public interface Editor extends UserDataHolder {

    Caret getCaret();

    Document getDocument();
}
