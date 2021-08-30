package com.tyron.code.ui.file;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.ViewHolder> {
    
    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_BACK = 1;
    
    public interface OnItemClickListener {
        void onItemClick(File file, int position);
    }
    
    private OnItemClickListener mListener;
    private final List<File> mFiles = new ArrayList<>();
    
    public void setOnItemClickListener(OnItemClickListener listener) {
        mListener = listener;
    }
    
    public void submitFile(File file) {
        mFiles.clear();
        
        File[] files = file.listFiles();
        if (files != null) {
            mFiles.addAll(List.of(files));
        }
        
        Collections.sort(mFiles, (p1, p2) -> {
            if (p1.isFile() && p2.isFile()) {
                return p1.getName().compareTo(p2.getName());
            }

            if (p1.isFile() && p2.isDirectory()) {
                return -1;
            }

            if (p1.isDirectory() && p2.isDirectory()) {
                return p1.getName().compareTo(p2.getName());
            }
            return 0;
        });
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int p2) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.file_manager_item, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        
        if (mListener != null) {
            view.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    File selected = null;
                    if (position != 0) {
                        selected = mFiles.get(position - 1);
                    }
                    mListener.onItemClick(selected, position);
                }
            });
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int p2) {
        int type = holder.getItemViewType();
        if (type == TYPE_BACK) {
            holder.bindBack();
        } else {
            holder.bind(mFiles.get(p2 - 1));
        }
    }

    @Override
    public int getItemCount() {
        return mFiles.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return TYPE_BACK;
        }
        return TYPE_NORMAL;
    }
    
    public static class ViewHolder extends RecyclerView.ViewHolder {
        
        public ImageView icon;
        public TextView name;
        
        public ViewHolder(View view) {
            super(view);
            
            icon = view.findViewById(R.id.icon);
            name = view.findViewById(R.id.name);
        }
        
        public void bind(File file) {
            name.setText(file.getName());
            
            if (file.isDirectory()) {
                icon.setImageResource(R.drawable.round_folder_24);
            } else if (file.isFile()) {
                icon.setImageResource(R.drawable.round_insert_drive_file_24);
            }
        }
        
        public void bindBack() {
            name.setText("...");
            icon.setImageResource(R.drawable.round_arrow_upward_24);
        }
    }
}

