package com.tyron.code.ui.editor;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tyron.completion.BuildConfig;
import com.tyron.completion.progress.ProgressManager;

import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.jetbrains.kotlin.utils.ReflectionUtilKt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
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

    private final List<CompletionItem> mItems = new ArrayList<>();

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
        mListView.setAdapter(mAdapter);
    }

    @Override
    public void setAdapter(EditorCompletionAdapter adapter) {
        super.setAdapter(adapter);

        mAdapter = adapter;
        mAdapter.attachValues(this, mItems);
        mAdapter.notifyDataSetInvalidated();
        mListView.setAdapter(adapter);
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
        if (mThread != null) {
            ProgressManager.getInstance().cancelThread(mThread);
        }
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

        AtomicReference<List<CompletionItem>> reference = new AtomicReference<>();

        CompletionPublisher publisher = new CompletionPublisher(mEditor.getHandler(), () -> {
            List<CompletionItem> newItems = reference.get();
            mItems.clear();
            mItems.addAll(newItems);
            mAdapter.notifyDataSetChanged();
            float newHeight = mAdapter.getItemHeight() * mAdapter.getCount();
            setSize(getWidth(), (int) Math.min(newHeight, mMaxHeight));
            if (!isShowing()) {
                show();
            }
            if (mAdapter.getCount() >= 1) {
                setCurrent(0);
            }
        }, mEditor.getEditorLanguage().getInterruptionLevel());
        reference.set(publisher.getItems());

        mThread = new CompletionThread(mRequestTime, publisher);
        mThread.setName("CompletionThread " + mRequestTime);
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
