package com.tyron.completion.java.insert;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.InsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Editor;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.type.TypeKind;

import java.util.function.Predicate;

public class MethodInsertHandler extends DefaultInsertHandler {

    private final ExecutableElement method;

    public MethodInsertHandler(ExecutableElement method, CompletionItem item) {
        super(CompletionUtils.JAVA_PREDICATE, item);
        this.method = method;

    }
    @Override
    public void handleInsert(Editor editor) {
        deletePrefix(editor);

        String commitText = item.commitText;
        boolean insertSemi = shouldInsertSemiColon();
        if (insertSemi) {
            commitText = commitText + ";";
        }
        int diff = 0;
        if (hasParameters()) {
            diff++;
            if (insertSemi) {
                diff++;
            }
        }

        insert(commitText, editor, false);
        editor.setSelection(editor.getCaret().getStartLine(), editor.getCaret().getStartColumn() - diff);
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
