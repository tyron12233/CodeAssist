package com.tyron.code.ui.file.action.file;

import android.content.DialogInterface;
import android.text.Editable;
import android.view.SubMenu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;
import com.tyron.code.R;
import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.file.action.ActionContext;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.common.util.SingleTextWatcher;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CreateDirectoryAction extends FileAction {
    @Override
    public boolean isApplicable(File file) {
        return file.isDirectory();
    }

    @Override
    public void addMenu(ActionContext context) {
        SubMenu subMenu = context.addSubMenu("new",
                context.getFragment().getString(R.string.menu_new));
        subMenu.add(R.string.menu_action_new_directory)
                .setOnMenuItemClickListener(i -> onMenuItemClick(context));
    }

    @SuppressWarnings("ConstantConditions")
    public boolean onMenuItemClick(ActionContext context) {
        File currentDir = context.getCurrentNode().getValue().getFile();
        AlertDialog dialog = new AlertDialog.Builder(context.getFragment().requireContext())
                .setView(R.layout.create_class_dialog)
                .setTitle(R.string.menu_action_new_directory)
                .setPositiveButton(R.string.create_class_dialog_positive, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener((d) -> {
            dialog.findViewById(R.id.til_class_type).setVisibility(View.GONE);
            TextInputLayout til = dialog.findViewById(R.id.til_class_name);
            EditText editText = dialog.findViewById(R.id.et_class_name);
            Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);

            til.setHint(R.string.directory_name);
            positive.setOnClickListener(v -> {
                File fileToCreate = new File(currentDir, editText.getText().toString());
                if (!fileToCreate.mkdirs()) {
                    new AlertDialog.Builder(context.getFragment().requireContext())
                            .setTitle(R.string.error)
                            .setMessage(R.string.error_dir_access)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    refreshTreeView(context);
                    dialog.dismiss();
                }
            });
            editText.addTextChangedListener(new SingleTextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    File file = new File(currentDir, editable.toString());
                    positive.setEnabled(!file.exists());
                }
            });
        });
        dialog.show();
        return true;
    }

    private void refreshTreeView(ActionContext context) {
        TreeNode<TreeFile> node = context.getCurrentNode();
        File fileToRefresh = node.getValue().getFile();

        Set<File> expandedNodes = getExpandedNodes(node);
        List<TreeNode<TreeFile>> newChildren = TreeUtil.getNodes(fileToRefresh, node.getLevel())
                .get(0).getChildren();
        setExpandedNodes(newChildren, expandedNodes);
        node.setChildren(newChildren);
        context.getTreeView().refreshTreeView();
    }

    private void setExpandedNodes(List<TreeNode<TreeFile>> nodeList, Set<File> expandedNodes) {
        for (TreeNode<TreeFile> treeFileTreeNode : nodeList) {
            if (expandedNodes.contains(treeFileTreeNode.getValue().getFile())) {
                treeFileTreeNode.setExpanded(true);
            }

            setExpandedNodes(treeFileTreeNode.getChildren(), expandedNodes);
        }
    }

    private Set<File> getExpandedNodes(TreeNode<TreeFile> node) {
        Set<File> expandedNodes = new HashSet<>();
        if (node.isExpanded()) {
            expandedNodes.add(node.getValue().getFile());
        }
        for (TreeNode<TreeFile> child : node.getChildren()) {
            if (child.getValue().getFile().isDirectory()) {
                expandedNodes.addAll(getExpandedNodes(child));
            }
        }
        return expandedNodes;
    }
}
