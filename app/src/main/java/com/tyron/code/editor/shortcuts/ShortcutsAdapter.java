package com.tyron.code.editor.shortcuts;

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

    private final List<ShortcutItem> mItems = new ArrayList<>();

    public ShortcutsAdapter(List<ShortcutItem> items) {
        mItems.addAll(items);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.shortcut_item, parent, false);
        ViewHolder holder = new ViewHolder(view);
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
            textView.setTextColor(0xffffffff);
        }

        public void bind(ShortcutItem item) {
            textView.setText(item.label);
        }
    }
}
