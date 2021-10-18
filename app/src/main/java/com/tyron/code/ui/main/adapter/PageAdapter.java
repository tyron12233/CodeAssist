package com.tyron.code.ui.main.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.DiffUtil;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.tyron.code.ui.editor.CodeEditorFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PageAdapter extends FragmentStateAdapter {

    private final List<File> data = new ArrayList<>();

    public PageAdapter(FragmentManager fm, Lifecycle lifecycle) {
        super(fm, lifecycle);
    }

    public void submitList(List<File> files) {
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
        return CodeEditorFragment.newInstance(data.get(p1));
    }

    @Nullable
    public File getItem(int position) {
        if (position > data.size() - 1) {
            return null;
        }
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        if (data.isEmpty() || position > data.size()) {
            return -1;
        }
        return data.get(position).getAbsolutePath().hashCode();
    }

    public int getPosition(File file) {
        if (containsItem(file.getAbsolutePath().hashCode())) {
            return data.indexOf(file);
        }
        return -1;
    }

    public List<File> getItems() {
        return data;
    }

    @Override
    public boolean containsItem(long itemId) {
        for (File file : data) {
            if (file.getAbsolutePath().hashCode() == itemId) {
                return true;
            }
        }
        return false;
    }
}
