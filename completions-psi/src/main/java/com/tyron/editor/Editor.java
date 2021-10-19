package com.tyron.editor;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

public interface Editor {

    Document getDocument();

    CaretModel getCaretModel();
}
