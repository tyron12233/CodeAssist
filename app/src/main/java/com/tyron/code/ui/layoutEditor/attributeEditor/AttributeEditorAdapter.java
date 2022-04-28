package com.tyron.code.ui.layoutEditor.attributeEditor;

import kotlin.Pair;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;

import java.util.ArrayList;
import java.util.List;

public class AttributeEditorAdapter extends RecyclerView.Adapter<AttributeEditorAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(int pos, Pair<String, String> attribute);
    }

    private List<Pair<String, String>> mAttributes = new ArrayList<>();

    public AttributeEditorAdapter() {

    }

    private OnItemClickListener mItemClickListener;

    public List<Pair<String, String>> getAttributes() {
        return new ArrayList<>(mAttributes);
    }
    public void setItemClickListener(OnItemClickListener listener) {
        mItemClickListener = listener;
    }

    public void submitList(List<kotlin.Pair<String, String>> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mAttributes.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return mAttributes.get(oldItemPosition).getFirst()
                        .equals(newList.get(newItemPosition).getFirst());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return mAttributes.get(oldItemPosition).getSecond()
                        .equals(newList.get(newItemPosition).getSecond());
            }
        });
        mAttributes.clear();
        mAttributes.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder viewHolder = new ViewHolder(new FrameLayout(parent.getContext()));

        viewHolder.itemView.findViewById(R.id.item_attribute).setOnClickListener(v -> {
            if (mItemClickListener != null) {
                int position = viewHolder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    mItemClickListener.onItemClick(position, mAttributes.get(position));
                }
            }
        });
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mAttributes.get(position));
    }

    @Override
    public int getItemCount() {
        return mAttributes.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView mKeyText;
        private final TextView mValueText;

        public ViewHolder(FrameLayout frameLayout) {
            super(frameLayout);
            frameLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View root = LayoutInflater.from(frameLayout.getContext())
                    .inflate(R.layout.attribute_editor_item, frameLayout);

            mKeyText = root.findViewById(R.id.textview_key);
            mValueText = root.findViewById(R.id.textview_value);
        }

        public void bind(Pair<String, String> item) {
            mKeyText.setText(item.getFirst());
            mValueText.setText(item.getSecond());
        }
    }
}
