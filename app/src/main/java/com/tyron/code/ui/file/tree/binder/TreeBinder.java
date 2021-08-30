package com.tyron.code.ui.file.tree.binder;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.tyron.code.R;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;

import tellh.com.recyclertreeview_lib.TreeNode;
import tellh.com.recyclertreeview_lib.TreeViewBinder;

public class TreeBinder extends TreeViewBinder<TreeBinder.ViewHolder> {

    @Override
    public ViewHolder provideViewHolder(View itemView) {
        return new ViewHolder(itemView);
    }

    @Override
    public void bindView(ViewHolder viewHolder, int i, TreeNode treeNode) {
        viewHolder.arrow.setRotation(0);
        viewHolder.arrow.setImageResource(R.drawable.ic_baseline_keyboard_arrow_right_24);
        int rotation = treeNode.isExpand() ? 90 : 0;
        viewHolder.arrow.setRotation(rotation);

        TreeFile treeFile = (TreeFile) treeNode.getContent();
        File file = treeFile.getFile();

        viewHolder.dirName.setText(file.getName());

        if (file.isDirectory()) {
            viewHolder.icon.setImageResource(R.drawable.round_folder_24);
        } else {
            viewHolder.arrow.setVisibility(View.GONE);
            viewHolder.icon.setImageResource(R.drawable.round_insert_drive_file_24);
        }

        viewHolder.arrow.setVisibility(treeNode.isLeaf() ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getLayoutId() {
        return R.layout.file_manager_item;
    }

    public static class ViewHolder extends TreeViewBinder.ViewHolder {

        public final ImageView arrow;
        public final ImageView icon;
        public final TextView dirName;

        public ViewHolder(View rootView) {
            super(rootView);

            arrow = rootView.findViewById(R.id.arrow);
            icon = rootView.findViewById(R.id.icon);
            dirName = rootView.findViewById(R.id.name);

        }
    }
}
