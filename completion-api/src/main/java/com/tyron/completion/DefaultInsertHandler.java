package com.tyron.completion;

import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import java.util.function.Predicate;

public class DefaultInsertHandler implements InsertHandler {

    private final Predicate<Character> predicate;
    protected final CompletionItem item;

    public DefaultInsertHandler(CompletionItem item) {
        this(CompletionUtils.JAVA_PREDICATE, item);
    }

    public DefaultInsertHandler(Predicate<Character> predicate, CompletionItem item) {
        this.predicate = predicate;
        this.item = item;
    }

    protected String getPrefix(String line, CharPosition position) {
        return CompletionUtils.computePrefix(line, position, predicate);
    }

    protected void deletePrefix(Editor editor) {
        Caret caret = editor.getCaret();
        String lineString = editor.getContent()
                .getLineString(caret.getStartLine());
        String prefix = getPrefix(lineString, getCharPosition(caret));
        int length = prefix.length();
        if (prefix.contains(".")) {
            length -= prefix.lastIndexOf('.') + 1;
        }
        editor.delete(caret.getStartLine(), caret.getStartColumn() - length, caret.getStartLine(),
                      caret.getStartColumn());
    }

    @Override
    public void handleInsert(Editor editor) {
        deletePrefix(editor);
        insert(item.commitText, editor, false);
    }

    protected void insert(String string, Editor editor, boolean calcSpace) {
        Caret caret = editor.getCaret();
        if (string.contains("\n")) {
            editor.insertMultilineString(caret.getStartLine(), caret.getStartColumn(), string);
        } else {
            if (calcSpace) {
                if (isEndOfLine(caret.getStartLine(), caret.getStartColumn(), editor)) {
                    string += " ";
                }
            }
            editor.insert(caret.getStartLine(), caret.getStartColumn(), string);
        }
    }

    protected void insert(String string, Editor editor) {
       insert(string, editor, true);
    }

    protected CharPosition getCharPosition(Caret caret) {
        return new CharPosition(caret.getStartLine(), caret.getEndColumn());
    }

    public boolean isEndOfLine(int line, int column, Editor editor) {
        String lineString = editor.getContent().getLineString(line);
        String substring = lineString.substring(column);
        return substring.trim().isEmpty();
    }
}
