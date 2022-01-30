package com.tyron.code.ui.wizard.adapter;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.CornerFamily;
import com.tyron.code.R;
import com.tyron.code.ui.wizard.WizardTemplate;
import com.tyron.common.util.AndroidUtilities;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class WizardTemplateAdapter extends RecyclerView.Adapter<WizardTemplateAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(WizardTemplate item, int position);
    }

    private final List<WizardTemplate> mItems = new ArrayList<>();
    private OnItemClickListener mListener;

    public WizardTemplateAdapter() {

    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        mListener = listener;
    }

    public void submitList(@NonNull List<WizardTemplate> newItems) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mItems.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(mItems.get(oldItemPosition), newItems.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(mItems.get(oldItemPosition), newItems.get(newItemPosition));
            }
        });
        mItems.clear();
        mItems.addAll(newItems);
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.wizard_template_item, parent, false);
        ViewHolder holder = new ViewHolder(view);
        view.setOnClickListener(view1 -> {
            if (mListener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    mListener.onItemClick(mItems.get(pos), pos);
                }
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

        public final ShapeableImageView icon;
        public final TextView name;

        public ViewHolder(View view) {
            super(view);

            icon = view.findViewById(R.id.template_icon);
            name = view.findViewById(R.id.template_name);
        }

        private void bind(WizardTemplate template) {
            name.setText(template.getName());

            File iconFile = new File(template.getPath(), "icon.png");
            if (iconFile.exists()) {
                icon.setImageURI(Uri.fromFile(iconFile));
                icon.setShapeAppearanceModel(icon.getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCorners(CornerFamily.ROUNDED, AndroidUtilities.dp(8))
                        .build());
            }
        }
    }
}
