package com.tyron.completion.xml.insert;

import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import org.jetbrains.kotlin.checkers.TextDiagnosticDescriptor;

public class AttributeInsertHandler extends DefaultXmlInsertHandler {

    private final String mValueToInsert;

    public AttributeInsertHandler(CompletionItem item) {
        this(null, item);
    }

    public AttributeInsertHandler(String value, CompletionItem item) {
        super(item);

        mValueToInsert = value;
    }

    @Override
    protected void insert(String string, Editor editor, boolean calcSpace) {
        super.insert(string, editor, calcSpace);

        Caret caret = editor.getCaret();
        int startLine = caret
                .getStartLine();
        int startColumn = caret
                .getStartColumn();
        String lineString = editor.getContent()
                .getLineString(startLine);
        String substring = lineString.substring(startColumn);
        if (substring.startsWith("=\"\"")) {
            editor.setSelection(startLine, startColumn + 2);
        } else if (substring.startsWith("=\"")) {
            // set the selection after the quote
            editor.setSelection(startLine, startColumn + 2);
            // insert the missing quote
            super.insert("\"", editor, false);
            // move back the selection inside the quote
            editor.setSelection(caret.getStartLine(), caret.getStartColumn() - 1);
        } else  if (substring.startsWith("=")) {
            super.insert("\"\"", editor, false);
            editor.setSelection(startLine, startColumn + 2);
        } else {
            super.insert("=\"\"", editor, false);
            editor.setSelection(startLine, startColumn + 2);
        }

        if (mValueToInsert != null) {
            super.insert(mValueToInsert, editor, false);
            editor.setSelection(caret.getStartLine(), caret.getStartColumn() + 1);
        }
    }
}
