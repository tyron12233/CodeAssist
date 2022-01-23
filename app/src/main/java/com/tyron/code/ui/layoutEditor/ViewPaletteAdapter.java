package com.tyron.code.ui.layoutEditor;

import android.content.ClipData;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;
import com.tyron.code.ui.layoutEditor.model.EditorDragState;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ViewPaletteAdapter extends RecyclerView.Adapter<ViewPaletteAdapter.ViewHolder> {

    private final List<ViewPalette> mViewPaletteList;

    public ViewPaletteAdapter() {
        mViewPaletteList = new ArrayList<>();
    }

    public void submitList(List<ViewPalette> newList) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mViewPaletteList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int old, int newPos) {
                return Objects.equals(mViewPaletteList.get(old), newList.get(newPos));
            }

            @Override
            public boolean areContentsTheSame(int old, int newPos) {
                return Objects.equals(mViewPaletteList.get(old), newList.get(newPos));
            }
        });
        mViewPaletteList.clear();
        mViewPaletteList.addAll(newList);
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout root = new FrameLayout(parent.getContext());
        return new ViewHolder(root);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mViewPaletteList.get(position));
    }

    @Override
    public int getItemCount() {
        return mViewPaletteList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final View mInflatedView;
        private final ImageView mIcon;
        private final TextView mName;

        public ViewHolder(FrameLayout view) {
            super(view);

            mInflatedView = LayoutInflater.from(view.getContext())
                    .inflate(R.layout.editor_palette_item, view);
            mIcon = mInflatedView.findViewById(R.id.icon);
            mName = mInflatedView.findViewById(R.id.name);
        }

        public void bind(ViewPalette item) {
            mIcon.setImageResource(item.getIcon());
            mName.setText(item.getName());

            mInflatedView.setOnLongClickListener(v -> {
                ClipData clipData = ClipData.newPlainText("", "");
                View.DragShadowBuilder dragShadowBuilder = new View.DragShadowBuilder(v);
                EditorDragState state = EditorDragState.fromPalette(item);
                ViewCompat.startDragAndDrop(v, clipData, dragShadowBuilder, state, 0);
                return true;
            });
        }
    }
}
