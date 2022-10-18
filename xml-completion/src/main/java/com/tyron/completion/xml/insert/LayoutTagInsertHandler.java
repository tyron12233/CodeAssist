package com.tyron.completion.xml.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.xml.BytecodeScanner;
import com.tyron.editor.Editor;

import org.apache.bcel.classfile.JavaClass;

import java.util.function.Predicate;

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
