package com.tyron.completion.xml.insert;

import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Editor;

public class AttributeInsertHandler extends DefaultXmlInsertHandler {

    public AttributeInsertHandler(CompletionItem item) {
        super(item);
    }

    @Override
    protected void insert(String string, Editor editor, boolean calcSpace) {
        super.insert(string, editor, calcSpace);
        editor.setSelection(editor.getCaret().getStartLine(), editor.getCaret().getStartColumn() - 1);
    }
}
