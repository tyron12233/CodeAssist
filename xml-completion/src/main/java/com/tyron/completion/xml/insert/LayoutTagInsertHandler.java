package com.tyron.completion.xml.insert;

import com.tyron.completion.model.CompletionItem;
import com.tyron.legacyEditor.Editor;

import org.apache.bcel.classfile.JavaClass;

public class LayoutTagInsertHandler extends DefaultXmlInsertHandler {

    private final JavaClass clazz;

    public LayoutTagInsertHandler(JavaClass clazz, CompletionItem item) {
        super(item);
        this.clazz = clazz;
    }

    @Override
    protected void insert(String string, Editor editor, boolean calcSpace) {
        super.insert(string, editor, calcSpace);
    }
}
