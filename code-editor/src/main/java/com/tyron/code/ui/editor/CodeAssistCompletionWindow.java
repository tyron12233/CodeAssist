package com.tyron.code.ui.editor;

import android.util.Log;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.tyron.completion.BuildConfig;
import com.tyron.completion.progress.ProgressManager;

import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.jetbrains.kotlin.utils.ReflectionUtilKt;

import java.lang.reflect.Field;

import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.CompletionLayout;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;

public class CodeAssistCompletionWindow extends EditorAutoCompletion {

    private static final String TAG = CodeAssistCompletionWindow.class.getSimpleName();

    private final CodeEditor mEditor;
    private CompletionLayout mLayout;
    private ListView mListView;
    private EditorCompletionAdapter mAdapter;

    /**
     * Create a panel instance for the given editor
     *
     * @param editor Target editor
     */
    public CodeAssistCompletionWindow(CodeEditor editor) {
        super(editor);

        mEditor = editor;
        mAdapter = ReflectionUtil.getField(EditorAutoCompletion.class,
                this, EditorCompletionAdapter.class, "mAdapter");
    }

    @Override
    public void setLayout(@NonNull CompletionLayout layout) {
        super.setLayout(layout);

        mLayout = layout;
        mListView = (ListView) layout.getCompletionList();
    }

    @Override
    public void setAdapter(EditorCompletionAdapter adapter) {
        super.setAdapter(adapter);

        mAdapter = adapter;
    }

    @Override
    public void select(int pos) {
        if (pos > mAdapter.getCount()) {
            return;
        }

        try {
            super.select(pos);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to select item", e);
            }
        }
    }

    @Override
    public void select() {
        try {
            super.select();
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to select item", e);
            }
        }
    }

    @Override
    public void cancelCompletion() {
        ProgressManager.getInstance().cancelThread(mThread);
        super.cancelCompletion();
    }

    @Override
    public void requireCompletion() {
        if (mCancelShowUp || !isEnabled()) {
            return;
        }
        Content text = mEditor.getText();
        if (text.getCursor().isSelected() || checkNoCompletion()) {
            hide();
            return;
        }
        if (System.nanoTime() - mRequestTime < mEditor.getProps().cancelCompletionNs) {
            hide();
            mRequestTime = System.nanoTime();
            return;
        }
        cancelCompletion();
        mRequestTime = System.nanoTime();

        setCurrent(-1);

        CompletionPublisher publisher = new CompletionPublisher(mEditor.getHandler(), () -> {
            mAdapter.notifyDataSetChanged();
            float newHeight = mAdapter.getItemHeight() * mAdapter.getCount();
            setSize(getWidth(), (int) Math.min(newHeight, mMaxHeight));
            if (!isShowing()) {
                show();
            }
        }, mEditor.getEditorLanguage().getInterruptionLevel());

        if (mAdapter instanceof CodeAssistCompletionAdapter) {
            ((CodeAssistCompletionAdapter) mAdapter).setItems(this, publisher.getItems());
        }
        // only change the adapter if it does not match with the previous one
        // to reduce flickering
        if (!mAdapter.equals(mListView.getAdapter())) {
            mListView.setAdapter(mAdapter);
        }
        mThread = new CompletionThread(mRequestTime, publisher);
        setLoading(true);
        mThread.start();
    }

    private void setCurrent(int pos) {
        try {
            Field field = EditorAutoCompletion.class.getDeclaredField("mCurrent");
            field.setAccessible(true);
            field.set(this, pos);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
