package com.tyron.code.ui.editor;

import android.os.Build;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.completion.java.drawable.CircleDrawable;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora2.R;

public class CompletionItemAdapter extends RecyclerView.Adapter<CompletionItemAdapter.ViewHolder> {

    public interface OnClickListener {
        void onClick(int position);
    }

    public interface OnLongClickListener {
        void onLongClick(View view, int position);
    }

    private String partial;

    private final List<CompletionItem> items = new ArrayList<>();
    private EditorAutoCompletion mWindow;

    private OnClickListener onClickListener;
    private OnLongClickListener longClickListener;

    private int mCurrentSelected = RecyclerView.NO_POSITION;
	
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
                    longClickListener.onLongClick(holder.itemView,pos);
                }
            }
            return true;
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(CompletionItemAdapter.ViewHolder holder, int position) {
        CompletionItem item = items.get(position);
        holder.bind(item, position == mCurrentSelected);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void attachAttributes(EditorAutoCompletion window, List<CompletionItem> result) {
        mWindow = window;
        this.items.clear();
        this.items.addAll(result);
//        this.partial = getAfterLastDot(mWindow.getPrefix());
		notifyDataSetChanged();
    }

    private String getAfterLastDot(String str) {
        if (str == null) {
            return "";
        }
        if (str.contains(".")) {
            str = str.substring(str.lastIndexOf(".") + 1);
        }
        return str;
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

    public void setSelection(int position) {
        int old = mCurrentSelected;
        mCurrentSelected = position;

        if (old != RecyclerView.NO_POSITION) {
            notifyItemChanged(old);
        }

        notifyItemChanged(position);
    }


    public class ViewHolder extends RecyclerView.ViewHolder {

        private final View mRoot;

        private final TextView mLabel;
        private final TextView mDesc;
        private final ImageView mIcon;

        public ViewHolder(View view) {
            super(view);
            mRoot = view;

            mLabel = view.findViewById(R.id.result_item_label);
            mDesc = view.findViewById(R.id.result_item_desc);
            mIcon = view.findViewById(R.id.result_item_image);
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        public void bind(CompletionItem item, boolean selected) {
            mLabel.setText(item.label);
            mDesc.setText(item.desc);
            mDesc.setTooltipText(item.desc);

            if (partial != null && partial.length() > 0) {
                if (item.label.toString().startsWith(partial)) {
                    ForegroundColorSpan span = new ForegroundColorSpan(
                            0xFFCC7832);

                    SpannableString spannableString = new SpannableString(item.label);
                    spannableString.setSpan(span, 0, partial.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    mLabel.setText(spannableString);
                }
            }

            if (selected) {
                mRoot.setBackgroundColor(0x40000000);
            } else {
                mRoot.setBackgroundColor(0xff2b2b2b);
            }
            
            mIcon.setVisibility(View.VISIBLE);
//            if (item.item != null) {
//                mIcon.setImageDrawable(new CircleDrawable(item.item.iconKind));
//            }
        }
    }
}
