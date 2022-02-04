package com.tyron.completion.xml.insert;

import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.Format;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class ValueInsertHandler extends DefaultXmlInsertHandler {

    private final AttributeInfo attributeInfo;

    public ValueInsertHandler(AttributeInfo attributeInfo, CompletionItem item) {
        super(item);

        this.attributeInfo = attributeInfo;
    }

    @Override
    protected void insert(String string, Editor editor) {
        super.insert(string, editor);

        Caret caret = editor.getCaret();
        int line = caret.getStartLine();
        int column = caret.getStartColumn();
        if (!attributeInfo.getFormats().contains(Format.FLAG)) {
            String lineString = editor.getContent().getLineString(line);
            if (lineString.charAt(column) == '"') {
                editor.setSelection(line, column + 1);
                editor.insertMultilineString(line, column + 1, "\n");
            }
        }
    }
}
