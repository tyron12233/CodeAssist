package com.tyron.code.ui.editor.adapter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.tyron.fileeditor.api.FileEditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PageAdapter extends RecyclerView.Adapter<PageAdapter.ViewHolder> {

    public static void getDiff(List<FileEditor> oldFiles, List<FileEditor> newFiles, ListUpdateCallback callback) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldFiles.size();
            }

            @Override
            public int getNewListSize() {
                return newFiles.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(oldFiles.get(oldItemPosition), newFiles.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(oldFiles.get(oldItemPosition), newFiles.get(newItemPosition));
            }
        });
        oldFiles.clear();
        oldFiles.addAll(newFiles);
        result.dispatchUpdatesTo(callback);
    }

    private final List<FileEditor> data = new ArrayList<>();

    public void submitList(List<FileEditor> files) {
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(new FrameLayout(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {

    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
