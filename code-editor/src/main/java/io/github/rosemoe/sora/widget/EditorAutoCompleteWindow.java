package io.github.rosemoe.sora.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.ui.editor.CompletionItemAdapter;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.text.TextAnalyzeResult;

public class EditorAutoCompleteWindow extends EditorBasePopupWindow {
    private final static String TIP = "Refreshing...";
    private final CodeEditor mEditor;
    private final LinearLayoutManager mLayoutManager;
    private final RecyclerView mListView;
    private final TextView mTip;
    private final GradientDrawable mBg;
    protected boolean mCancelShowUp = false;
    private int mCurrent = 0;
    private long mRequestTime;
    private String mLastPrefix;
    private AutoCompleteProvider mProvider;
    private boolean mLoading;
    private int mMaxWidth;
    private int mMaxHeight;
    private final CompletionItemAdapter mAdapter;

    /**
     * Create a panel instance for the given editor
     *
     * @param editor Target editor
     */
    public EditorAutoCompleteWindow(CodeEditor editor) {
        super(editor);
        mEditor = editor;

        RelativeLayout layout = new RelativeLayout(mEditor.getContext());
        setContentView(layout);

        mAdapter = new CompletionItemAdapter();
        mAdapter.setOnItemClickListener(position -> {
            try {
                select(position);
            } catch (Exception e) {
                Toast.makeText(mEditor.getContext(), Log.getStackTraceString(e), Toast.LENGTH_SHORT).show();
            }
        });

        mLayoutManager = new LinearLayoutManager(mEditor.getContext());
        mListView = new RecyclerView(mEditor.getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                heightSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        mListView.setLayoutManager(mLayoutManager);
        mListView.setAdapter(mAdapter);

        layout.addView(mListView, new ViewGroup.LayoutParams(-1, -2));

        mTip = new TextView(mEditor.getContext());
        mTip.setText(TIP);
        mTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTip.setBackgroundColor(0xeeeeeeee);
        mTip.setTextColor(0xff000000);
        layout.addView(mTip);
        ((RelativeLayout.LayoutParams) mTip.getLayoutParams()).addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(1);
        layout.setBackgroundDrawable(gd);
        mBg = gd;

        applyColorScheme();
        setLoading(true);
        setWindowLayoutMode(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * Not needed
     */
    protected void setAdapter(EditorCompletionAdapter adapter) {

    }

    public void setCancelShowUp(boolean val) {
        mCancelShowUp = val;
    }

    @Override
    public void show() {
        if (mCancelShowUp) {
            return;
        }
        super.show();
    }

    @Override
    public void hide() {
        super.hide();
    }

    public Context getContext() {
        return mEditor.getContext();
    }

    public int getCurrentPosition() {
        return mCurrent;
    }

    /**
     * Set a auto completion items provider
     *
     * @param provider New provider.can not be null
     */
    public void setProvider(AutoCompleteProvider provider) {
        mProvider = provider;
    }

    /**
     * Apply colors for self
     */
    public void applyColorScheme() {
        EditorColorScheme colors = mEditor.getColorScheme();
        mBg.setStroke(2, 0xff575757);
        mBg.setColor(0xff2b2b2b);
    }

    /**
     * Change layout to loading/idle
     *
     * @param state Whether loading
     */
    public void setLoading(boolean state) {
        mLoading = state;
        if (state) {
            mEditor.postDelayed(() -> {
                if (mLoading) {
                    mTip.setVisibility(View.VISIBLE);
                }
            }, 300);
        } else {
            mTip.setVisibility(View.GONE);
        }
        //mListView.setVisibility((!state) ? View.VISIBLE : View.GONE);
        update();
    }

    /**
     * Move selection down
     */
    public void moveDown() {
        if (mCurrent + 1 >= Objects.requireNonNull(mListView.getAdapter()).getItemCount()) {
            return;
        }
        mCurrent++;
        ensurePosition();
    }

    /**
     * Move selection up
     */
    public void moveUp() {
        if (mCurrent - 1 < 0) {
            return;
        }
        mCurrent--;
        ensurePosition();
    }

    /**
     * Make current selection visible
     */
    private void ensurePosition() {
        mListView.scrollToPosition(mCurrent);
        mAdapter.setSelection(mCurrent);
    }

    /**
     * Select current position
     */
    public void select() {
        select(mCurrent);
    }

    private String selectedItem;

    /**
     * Select the given position
     *
     * @param pos Index of auto complete item
     */
    @SuppressLint("NewApi")
    public void select(int pos) {
        if (mAdapter.getItemCount() == 0) {
            return;
        }
        if (pos > mAdapter.getItemCount()) {
            return;
        }
        CompletionItem item = mAdapter.getItem(pos);
        Cursor cursor = mEditor.getCursor();

        if (mEditor.getOnCompletionItemSelectedListener() != null) {
            mEditor.getOnCompletionItemSelectedListener()
                    .onItemSelect(this, item);
            return;
        }
        if (!cursor.isSelected()) {
            mCancelShowUp = true;

            int length = mLastPrefix.length();

            if (mLastPrefix.contains(".")) {
                length -= mLastPrefix.lastIndexOf(".") + 1;
            }
            mEditor.getText().delete(cursor.getLeftLine(), cursor.getLeftColumn() - length, cursor.getLeftLine(), cursor.getLeftColumn());

            selectedItem = item.commit;
            // will be invoked automatically if item.commit isn't multiline
            cursor.onCommitMultilineText(item.commit);

            if (item.commit != null && item.cursorOffset != item.commit.length()) {
                int delta = (item.commit.length() - item.cursorOffset);
                int newSel = Math.max(mEditor.getCursor().getLeft() - delta, 0);
                CharPosition charPosition = mEditor.getCursor().getIndexer().getCharPosition(newSel);
                mEditor.setSelection(charPosition.line, charPosition.column);
            }

            if (item.item.additionalTextEdits != null) {
                for (TextEdit edit : item.item.additionalTextEdits) {
                    applyTextEdit(edit);
                }
            }
            mCancelShowUp = false;
        }
        mEditor.postHideCompletionWindow();
    }

    public String getLastPrefix() {
        return mLastPrefix;
    }
    public void setSelectedItem(String item) {
        selectedItem = item;
    }

    // only applies for single line edits
    public void applyTextEdit(TextEdit edit) {
        Range range = edit.range;

        if (range.start.equals(range.end)) {
            mEditor.getText().insert(range.start.line, range.start.column, edit.newText);
        }
    }

    /**
     * Get prefix set
     *
     * @return The previous prefix
     */
    public String getPrefix() {
        return mLastPrefix;
    }

    /**
     * Set prefix for auto complete analysis
     *
     * @param prefix The user's input code's prefix
     */
    public void setPrefix(final String prefix) {
        if (mCancelShowUp) {
            return;
        }

        if (getAfterLastDot(prefix).equals(selectedItem) && !prefix.endsWith(".")) {
            selectedItem = "";
            return;
        }

        if (isShowing()) {
            setLoading(true);
        } else {
            mAdapter.attachAttributes(this, Collections.emptyList());
        }

        mLastPrefix = prefix;
        mRequestTime = System.currentTimeMillis();
        mPreviousThread = new MatchThread(mRequestTime, prefix);
        mPreviousThread.start();
    }

    public void setMaxWidth(int maxWidth) {
        mMaxWidth = maxWidth;
    }

    public void setMaxHeight(int height) {
        mMaxHeight = height;
    }

    /**
     * Display result of analysis
     *
     * @param results     Items of analysis
     * @param requestTime The time that this thread starts
     */
    public void displayResults(final List<CompletionItem> results, long requestTime) {
        if (mLastPrefix.equals(selectedItem)) {
            selectedItem = "";
            return;
        }

        mListView.post(() -> {
            setLoading(false);
            if (results == null || results.isEmpty()) {
                hide();
                return;
            }

            mAdapter.attachAttributes(this, results);
            mListView.scrollToPosition(0);
            mCurrent = 0;
            mAdapter.setSelection(0);

            if (isShowing()) {
                int newHeight = 300;
                update(getWidth(), Math.min(newHeight, mMaxHeight));
            }
        });
    }

    private volatile Thread mPreviousThread;

    /**
     * Analysis thread
     *
     * @author Rose
     */
    private class MatchThread extends Thread {

        private final long mTime;
        private final String mPrefix;
        private final boolean mInner;
        private final TextAnalyzeResult mColors;
        private final int mLine;
        private final int mColumn;
        private final AutoCompleteProvider mLocalProvider = mProvider;

        public MatchThread(long requestTime, String prefix) {
            mTime = requestTime;
            mPrefix = prefix;
            mColors = mEditor.getTextAnalyzeResult();
            mLine = mEditor.getCursor().getLeftLine();
            mColumn = mEditor.getCursor().getLeftColumn();
            mInner = (!mEditor.isHighlightCurrentBlock()) || (mEditor.getBlockIndex() != -1);
        }

        @Override
        public void run() {
            try {
                displayResults(mLocalProvider.getAutoCompleteItems(mPrefix, mColors, mLine, mColumn), mTime);
            } catch (InterruptedException e) {
                Log.d("MatchThread", "Thread is interrupted, exiting");
            }
        }
    }


    private String getAfterLastDot(String str) {
        if (str == null) {
            return "";
        }
        if (str.contains(".")) {
            str = str.substring(str.lastIndexOf(".") + 1);
        }
        return str;
    }
}