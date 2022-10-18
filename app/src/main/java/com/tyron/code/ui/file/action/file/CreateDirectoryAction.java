package com.tyron.code.ui.file.action.file;

import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.common.util.SingleTextWatcher;

import java.io.File;

public class CreateDirectoryAction extends FileAction {

    public static final String ID = "fileManagerCreateDirectoryAction";

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.menu_action_new_directory);
    }

    @Override
    public boolean isApplicable(File file) {
        return file.isDirectory();
    }

    private void refreshTreeView(TreeNode<TreeFile> currentNode, TreeView<?> treeView) {
        TreeUtil.updateNode(currentNode);
        treeView.refreshTreeView();
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        TreeFileManagerFragment fragment = (TreeFileManagerFragment) e.getData(CommonDataKeys.FRAGMENT);
        File currentDir = e.getData(CommonDataKeys.FILE);
        TreeNode<TreeFile> currentNode = e.getData(CommonFileKeys.TREE_NODE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
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
                ProgressManager progress = ProgressManager.getInstance();
                progress.runNonCancelableAsync(() -> {
                    File fileToCreate = new File(currentDir, editText.getText().toString());
                    if (!fileToCreate.mkdirs()) {
                        progress.runLater(() -> {
                            new MaterialAlertDialogBuilder(fragment.requireContext())
                                    .setTitle(R.string.error)
                                    .setMessage(R.string.error_dir_access)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        });
                    } else {
                        progress.runLater(() -> {
                            if (fragment == null || fragment.isDetached()) {
                                return;
                            }
                            refreshTreeView(currentNode, fragment.getTreeView());
                            dialog.dismiss();
                        });
                    }
                });


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
    }

}
