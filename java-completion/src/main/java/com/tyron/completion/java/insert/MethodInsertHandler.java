package com.tyron.completion.java.insert;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.InsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;

import java.util.function.Predicate;

public class MethodInsertHandler extends DefaultInsertHandler {

    private boolean includeParen;
    private final ExecutableElement method;

    public MethodInsertHandler(ExecutableElement method, boolean includeParen, CompletionItem item) {
        super(CompletionUtils.JAVA_PREDICATE, item);
        this.method = method;
        this.includeParen = includeParen;
    }

    public MethodInsertHandler(ExecutableElement method, CompletionItem item) {
        this(method, false, item);
    }

    public MethodInsertHandler(ExecutableElement method, CompletionItem item, boolean includeParen) {
        this(method, includeParen , item);
    }

    @Override
    public void handleInsert(Editor editor) {
        deletePrefix(editor);

        String commitText = item.commitText;
        Caret caret = editor.getCaret();
        boolean isEndOfLine = isEndOfLine(caret.getStartLine(), caret.getStartColumn(), editor);
        boolean endsWithParen = StringSearch.endsWithParen(editor.getContent(), editor.getCaret().getStart());
        if (endsWithParen) {
            if (commitText.endsWith("()")) {
                commitText = commitText.substring(0, commitText.length() - 2);
            } else if (commitText.endsWith("();")) {
                commitText = commitText.substring(0, commitText.length() - 3);
            }
        } else if (includeParen) {
            commitText += "()";
        }
        boolean insertSemi = shouldInsertSemiColon() && isEndOfLine;
        if (insertSemi) {
            commitText = commitText + ";";
        }

        insert(commitText, editor, false);

        String lineString = editor.getContent().getLineString(caret.getStartLine());
        int startIndex = caret.getStartColumn() - commitText.length();
        int offset = 0;
        if (hasParameters()) {
            offset = lineString.indexOf('(', startIndex);
        } else {
            offset = lineString.indexOf(')', startIndex);

            int semicolon = lineString.indexOf(';', startIndex);
            // if the text contains semicolon and the semicolon is right next to the ')' character
            if (semicolon == offset + 1) {
                offset = semicolon;
            }
        }
        if (offset != -1) {
            editor.setSelection(caret.getStartLine(), offset + 1);
        }
    }

    private boolean hasParameters() {
        return !method.getParameters().isEmpty();
    }

    private boolean shouldInsertSemiColon() {
        if (method.getReturnType().getKind() == TypeKind.VOID) {
            return true;
        }
        return method.getReturnType().getKind().isPrimitive();
    }
}
