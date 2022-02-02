package com.tyron.code.ui.editor;

import android.widget.AdapterView;

import com.tyron.completion.progress.ProgressManager;

import java.lang.reflect.Field;

import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.CompletionLayout;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;

public class CodeAssistCompletionWindow extends EditorAutoCompletion {

    private final CodeEditor mEditor;

    /**
     * Create a panel instance for the given editor
     *
     * @param editor Target editor
     */
    public CodeAssistCompletionWindow(CodeEditor editor) {
        super(editor);

        mEditor = editor;
    }

    @Override
    public void cancelCompletion() {
        Thread thread = getField("mThread");
        if (thread != null) {
            ProgressManager.getInstance().cancelThread(thread);
        }
        super.cancelCompletion();
    }

    private <T> T getField(String fieldName) {
        try {
            Field field = EditorAutoCompletion.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object o = field.get(this);
            if (o != null) {
                return (T) o;
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
