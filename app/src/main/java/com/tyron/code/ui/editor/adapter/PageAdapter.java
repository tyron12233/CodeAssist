package com.tyron.code.ui.editor.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.DiffUtil;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.tyron.code.ui.editor.CodeEditorFragment;
import com.tyron.code.ui.editor.api.FileEditor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PageAdapter extends FragmentStateAdapter {

    private final List<FileEditor> data = new ArrayList<>();

    public PageAdapter(FragmentManager fm, Lifecycle lifecycle) {
        super(fm, lifecycle);
    }

    public void submitList(List<FileEditor> files) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return data.size();
            }

            @Override
            public int getNewListSize() {
                return files.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(data.get(oldItemPosition), files.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(data.get(oldItemPosition), files.get(newItemPosition));
            }
        });
        data.clear();
        data.addAll(files);
        result.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    @NonNull
    @Override
    public Fragment createFragment(int p1) {
        return data.get(p1).getFragment();
    }
}
