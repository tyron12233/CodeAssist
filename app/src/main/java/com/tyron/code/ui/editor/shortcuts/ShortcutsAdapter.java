package com.tyron.code.ui.editor.shortcuts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;

import java.util.ArrayList;
import java.util.List;

public class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {

    public interface OnShortcutSelected {
        void onShortcutClicked(ShortcutItem item, int position);
    }

    private final List<ShortcutItem> mItems = new ArrayList<>();
    private OnShortcutSelected mOnShortcutSelectedListener;

    public ShortcutsAdapter(List<ShortcutItem> items) {
        mItems.addAll(items);
    }

    public void setOnShortcutSelectedListener(OnShortcutSelected listener) {
        mOnShortcutSelectedListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.shortcut_item, parent, false);
        ViewHolder holder = new ViewHolder(view);

        view.setOnClickListener(view1 -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION && mOnShortcutSelectedListener != null) {
                mOnShortcutSelectedListener.onShortcutClicked(mItems.get(position), position);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView textView;

        public ViewHolder(View view) {
            super(view);

            textView = view.findViewById(R.id.shortcut_label);
        }

        public void bind(ShortcutItem item) {
            textView.setText(item.label);
        }
    }
}
