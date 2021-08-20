package com.tyron.code.editor;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;
import io.github.rosemoe.editor.struct.CompletionItem;
import java.util.ArrayList;
import android.widget.TextView;
import android.widget.ImageView;
import com.tyron.code.R;
import android.view.LayoutInflater;
import io.github.rosemoe.editor.widget.EditorAutoCompleteWindow;
import android.view.View.OnLongClickListener;

public class CompletionItemAdapter extends RecyclerView.Adapter<CompletionItemAdapter.ViewHolder> {
    
    public interface OnClickListener {
        void onClick(int position);
    }
    
    public interface OnLongClickListener {
        void onLongClick(int position);
    }
    
    private List<CompletionItem> items = new ArrayList<>();
    private EditorAutoCompleteWindow mWindow;
    
    private OnClickListener onClickListener;
    private OnLongClickListener longClickListener;
    
    @Override
    public CompletionItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.completion_result_item, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int pos = holder.getAdapterPosition();
                
                if (pos != RecyclerView.NO_POSITION) {
                    if (onClickListener != null) {
                        onClickListener.onClick(pos);
                    }
                }
            }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View p1) {
                int pos = holder.getAdapterPosition();

                if (pos != RecyclerView.NO_POSITION) {
                    if (longClickListener != null) {
                        longClickListener.onLongClick(pos);
                    }
                }
                return true;
            }                     
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
    }
    
    public CompletionItem getItem(int position) {
        return items.get(position);
    }
    
    public void setOnItemLongClickListener(OnLongClickListener listener) {
        longClickListener = listener;
    }
    
    public void setOnItemClickListener(OnClickListener listener) {
        onClickListener = listener;
    }
    
    
    public static class ViewHolder extends RecyclerView.ViewHolder {
        
        private TextView mLabel;
        private TextView mDesc;
        private ImageView mIcon;
        
        public ViewHolder(View view) {
            super(view);
            
            mLabel = view.findViewById(R.id.result_item_label);
            mDesc = view.findViewById(R.id.result_item_desc);
            mIcon = view.findViewById(R.id.result_item_image);
        }
        
        public void bind(CompletionItem item) {
            mLabel.setText(item.label);
            mDesc.setText(item.desc);
            
            if (item.icon != null) {
                mIcon.setVisibility(View.VISIBLE);
                mIcon.setImageDrawable(item.icon);
            } else {
                mIcon.setVisibility(View.GONE);
            }
        }
    }
}
