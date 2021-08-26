package com.tyron.code.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;
import com.tyron.code.editor.drawable.CircleDrawable;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.editor.struct.CompletionItem;
import io.github.rosemoe.editor.widget.EditorAutoCompleteWindow;

public class CompletionItemAdapter extends RecyclerView.Adapter<CompletionItemAdapter.ViewHolder> {

    public interface OnClickListener {
        void onClick(int position);
    }

    public interface OnLongClickListener {
        void onLongClick(int position);
    }

    private final List<CompletionItem> items = new ArrayList<>();
    private EditorAutoCompleteWindow mWindow;

    private OnClickListener onClickListener;
    private OnLongClickListener longClickListener;
	
    @NonNull
    @Override
    public CompletionItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.completion_result_item, parent, false);
        final ViewHolder holder = new ViewHolder(view);

        view.setOnClickListener(view1 -> {
            int pos = holder.getBindingAdapterPosition();

            if (pos != RecyclerView.NO_POSITION) {
                if (onClickListener != null) {
                    onClickListener.onClick(pos);
                }
            }
        });
        view.setOnLongClickListener(p1 -> {
            int pos = holder.getBindingAdapterPosition();

            if (pos != RecyclerView.NO_POSITION) {
                if (longClickListener != null) {
                    longClickListener.onLongClick(pos);
                }
            }
            return true;
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(CompletionItemAdapter.ViewHolder holder, int position) {
        CompletionItem item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void attachAttributes(EditorAutoCompleteWindow window, List<CompletionItem> result) {
        mWindow = window;
        this.items.clear();
        this.items.addAll(result);
		notifyDataSetChanged();
    }

    public CompletionItem getItem(int position) {
		if (items.size() < position) {
			return null;
		}
        return items.get(position);
    }

    public void setOnItemLongClickListener(OnLongClickListener listener) {
        longClickListener = listener;
    }

    public void setOnItemClickListener(OnClickListener listener) {
        onClickListener = listener;
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView mLabel;
        private final TextView mDesc;
        private final ImageView mIcon;

        public ViewHolder(View view) {
            super(view);

            mLabel = view.findViewById(R.id.result_item_label);
            mDesc = view.findViewById(R.id.result_item_desc);
            mIcon = view.findViewById(R.id.result_item_image);
        }

        public void bind(CompletionItem item) {
            mLabel.setText(item.label);
            mDesc.setText(item.desc);

            
            mIcon.setVisibility(View.VISIBLE);
            mIcon.setImageDrawable(new CircleDrawable(item.item.iconKind));

        }
    }
}
