package com.tyron.code.ui.legacyEditor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;

import com.google.android.material.tabs.TabLayout;
import com.tyron.code.ui.legacyEditor.adapter.PageAdapter;
import com.tyron.code.ui.main.TextEditorState;
import com.tyron.fileeditor.api.FileEditor;

import java.util.List;
import java.util.Objects;

public class EditorTabUtil {

    public static void updateTabLayout(@NonNull TabLayout mTabLayout, List<FileEditor> oldList, List<FileEditor> files) {
        PageAdapter.getDiff(oldList, files, new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
                FileEditor editor = files.get(position);
                TabLayout.Tab tab = getTabLayout(editor);
                mTabLayout.addTab(tab, position, false);
                mTabLayout.selectTab(tab, true);
            }

            @Override
            public void onRemoved(int position, int count) {
                for (int i = 0; i < count; i++) {
                    mTabLayout.removeTabAt(position);
                }
            }

            @Override
            public void onMoved(int fromPosition, int toPosition) {

            }

            @Override
            public void onChanged(int position, int count, @Nullable Object payload) {

            }

            private TabLayout.Tab getTabLayout(FileEditor editor) {
                TabLayout.Tab tab = mTabLayout.newTab();
                tab.setText(editor.getFile().getName());
                return tab;
            }
        });
    }

    public static void updateTabLayoutState(TabLayout tablayout,
                                       List<TextEditorState> oldList,
                                       List<TextEditorState> editors) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return editors.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(oldList.get(oldItemPosition).getFile(), editors.get(newItemPosition).getFile());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(oldList.get(oldItemPosition).getFile(), editors.get(newItemPosition).getFile());
            }
        });
        result.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
                TextEditorState editor = editors.get(position);
                TabLayout.Tab tab = getTabLayout(editor);
                tablayout.addTab(tab, position, false);
                tablayout.selectTab(tab, true);
            }

            @Override
            public void onRemoved(int position, int count) {
                for (int i = 0; i < count; i++) {
                    tablayout.removeTabAt(position);
                }
            }

            @Override
            public void onMoved(int fromPosition, int toPosition) {

            }

            @Override
            public void onChanged(int position, int count, @Nullable Object payload) {

            }

            private TabLayout.Tab getTabLayout(TextEditorState editor) {
                TabLayout.Tab tab = tablayout.newTab();
                tab.setText(editor.getFile().getName());
                return tab;
            }
        });
    }
}
