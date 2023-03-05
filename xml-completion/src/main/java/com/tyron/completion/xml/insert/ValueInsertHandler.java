package com.tyron.completion.xml.insert;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.resources.ResourceUrl;
import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ValueInsertHandler extends DefaultXmlInsertHandler {

    private final AttrResourceValue attributeInfo;
    private final ResourceUrl selfReference;

    public ValueInsertHandler(AttrResourceValue attributeInfo, CompletionItem item) {
        this(null, attributeInfo, item);
    }

    public ValueInsertHandler(@Nullable ResourceUrl selfReference,
                              @NotNull AttrResourceValue attribute,
                              @Nullable CompletionItem completionItem) {
        super(completionItem);
        this.selfReference = selfReference;
        this.attributeInfo = attribute;
    }

    @Override
    protected void insert(String string, Editor editor, boolean calcSpace) {
        super.insert(string, editor, calcSpace);

        Caret caret = editor.getCaret();
        int line = caret.getStartLine();
        int column = caret.getStartColumn();
        if (!attributeInfo.getFormats().contains(AttributeFormat.FLAGS)) {
            String lineString = editor.getContent().getLineString(line);
            if (lineString.charAt(column) == '"') {
                editor.setSelection(line, column + 1);
                editor.insertMultilineString(line, column + 1, "\n");
            }
        }
    }
}
