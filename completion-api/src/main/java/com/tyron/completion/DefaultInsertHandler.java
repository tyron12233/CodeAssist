package com.tyron.completion;

import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import java.util.function.Predicate;

public class DefaultInsertHandler implements InsertHandler {

    private final Predicate<Character> predicate;
    private final String commitText;

    public DefaultInsertHandler(Predicate<Character> predicate, String commitText) {
        this.predicate = predicate;
        this.commitText = commitText;
    }

    @Override
    public void handleInsert(Editor editor) {
        Caret caret = editor.getCaret();
        String lineString = editor.getContent().getLineString(caret.getStartLine());
        String prefix = CompletionUtils.computePrefix(lineString, getCharPosition(caret), predicate);
        int length = prefix.length();
        if (prefix.contains(".")) {
            length -= prefix.lastIndexOf('.') + 1;
        }
        editor.delete(caret.getStartLine(), caret.getStartColumn() - length,
                caret.getStartLine(), caret.getStartColumn());
        editor.insert(caret.getStartLine(), caret.getStartColumn(), commitText);
    }

    private CharPosition getCharPosition(Caret caret) {
        return new CharPosition(caret.getStartLine(), caret.getEndColumn());
    }
}
