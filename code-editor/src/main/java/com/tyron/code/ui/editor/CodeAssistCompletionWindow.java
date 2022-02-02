package com.tyron.code.ui.editor;

import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.completion.progress.ProgressManager;

import java.lang.reflect.Field;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;
import io.github.rosemoe.sora2.interfaces.AutoCompleteProvider;

public class CodeAssistCompletionWindow extends EditorAutoCompletion {

    /**
     * Create a panel instance for the given editor
     *
     * @param editor Target editor
     */
    public CodeAssistCompletionWindow(CodeEditor editor) {
        super(editor);
    }

    @Override
    public void cancelCompletion() {
        try {
            Field field = EditorAutoCompletion.class.getDeclaredField("mThread");
            field.setAccessible(true);
            Thread thread = (Thread) field.get(this);
            if (thread != null) {
                ProgressManager.getInstance().cancelThread(thread);
            }
        } catch (Throwable e) {
            // should not happen
            throw new Error(e);
        }
        super.cancelCompletion();
    }
}
