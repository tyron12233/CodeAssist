package com.tyron.code.ui.library.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.LayoutParams;

import com.google.common.collect.ImmutableList;
import com.tyron.code.R;
import com.tyron.resolver.model.Dependency;

import java.util.ArrayList;
import java.util.List;

public class LibraryManagerAdapter extends RecyclerView.Adapter<LibraryManagerAdapter.ViewHolder> {

    public interface ItemLongClickListener {
        void onItemLongClick(View view, Dependency item);
    }

    private final List<Dependency> mData = new ArrayList<>();
    private ItemLongClickListener mListener;

    public LibraryManagerAdapter() {

    }

    public void setItemLongClickListener(ItemLongClickListener listener) {
        mListener = listener;
    }

    public void addDependency(Dependency dependency) {
        List<Dependency> arrayList = new ArrayList<>(mData);
        arrayList.add(dependency);
        submitList(arrayList);
    }

    public void removeDependency(Dependency dependency) {
        List<Dependency> arrayList = new ArrayList<>(mData);
        arrayList.remove(dependency);
        submitList(arrayList);
    }

    public List<Dependency> getData() {
        return ImmutableList.copyOf(mData);
    }

    public void submitList(List<Dependency> newData) {
        DiffUtil.Callback callback = new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mData.size();
            }

            @Override
            public int getNewListSize() {
                return newData.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return mData.get(oldItemPosition).equals(newData.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return mData.get(oldItemPosition).equals(newData.get(newItemPosition));
            }
        };
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(callback);
        mData.clear();
        mData.addAll(newData);
        result.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout view = new FrameLayout(parent.getContext());
        view.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        ViewHolder viewHolder = new ViewHolder(view);
        view.setOnLongClickListener(v -> {
            int pos = viewHolder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                if (mListener != null) {
                    mListener.onItemLongClick(view, mData.get(pos));
                }
            }
            return true;
        });
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position == 0) {
            holder.mDivider.setVisibility(View.GONE);
        } else {
            holder.mDivider.setVisibility(View.VISIBLE);
        }
        holder.bind(mData.get(position));
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        public final View mDivider;
        private final TextView mTextView;

        public ViewHolder(FrameLayout view) {
            super(view);

            LayoutInflater inflater = LayoutInflater.from(view.getContext());
            View root = inflater.inflate(R.layout.library_manager_item, view);
            mDivider = root.findViewById(R.id.divider);
            mTextView = root.findViewById(R.id.item_text);
        }

        public void bind(Dependency dependency) {
            mTextView.setText(dependency.toString());
        }
    }
}
