package com.tyron.completion.xml.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import java.util.function.Predicate;

public class DefaultXmlInsertHandler extends DefaultInsertHandler {

    private static final Predicate<Character> DEFAULT_PREDICATE =
            ch -> Character.isJavaIdentifierPart(ch) ||
                  ch == '@' ||
                  ch == '<' ||
                  ch == '/' ||
                  ch == ':' ||
                  ch == '.';

    public DefaultXmlInsertHandler(CompletionItem item) {
        super(DEFAULT_PREDICATE, item);
    }

    @Override
    protected void deletePrefix(Editor editor) {
        Caret caret = editor.getCaret();
        String lineString = editor.getContent()
                .getLineString(caret.getStartLine());
        String prefix = getPrefix(lineString, getCharPosition(caret));
        int length = prefix.length();
        editor.delete(caret.getStartLine(), caret.getStartColumn() - length, caret.getStartLine(),
                      caret.getStartColumn());
    }
}
