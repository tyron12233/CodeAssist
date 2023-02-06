package com.tyron.code.language;

import com.tyron.completion.java.drawable.CircleDrawable;
import com.tyron.editor.Editor;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * A wrapper for {@link com.tyron.completion.model.CompletionItem}
 */
@Deprecated
public class CompletionItemWrapper extends CompletionItem {
    private final com.tyron.completion.model.CompletionItem item;

    public CompletionItemWrapper(com.tyron.completion.model.CompletionItem item) {
        super(item.label, item.detail, new CircleDrawable(item.iconKind));
        this.item = item;
    }

    @Override
    public void performCompletion(CodeEditor editor, Content text, int line, int column) {
        if (!(editor instanceof Editor)) {
            editor.insertText(item.commitText, text.getCharIndex(line, column));
            return;
        }

        Editor rawEditor = ((Editor) editor);
        item.handleInsert(rawEditor);
    }
}
