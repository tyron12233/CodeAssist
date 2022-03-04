package com.tyron.code.ui.file.action.file;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.github.angads25.filepicker.view.FilePickerDialog;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class ImportDirectoryAction extends FileAction {

    public static final String ID = "fileManagerImportDirectoryAction";

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
        TreeFileManagerFragment fragment =
                (TreeFileManagerFragment) e.getData(CommonDataKeys.FRAGMENT);
        File currentDir = e.getData(CommonDataKeys.FILE);
        TreeNode<TreeFile> currentNode = e.getData(CommonFileKeys.TREE_NODE);

        DialogProperties properties = new DialogProperties();
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.root = Environment.getExternalStorageDirectory();
        properties.error_dir = fragment.requireContext().getExternalFilesDir(null);

        FilePickerDialog dialog = new FilePickerDialog(fragment.requireContext(), properties);
        dialog.setDialogSelectionListener(files -> {
            ProgressManager.getInstance().runNonCancelableAsync(() -> {
                String file = files[0];
                try {
                    FileUtils.copyDirectoryToDirectory(new File(file), currentDir);
                } catch (IOException ioException) {
                    ProgressManager.getInstance().runLater(() -> {
                        if (fragment.isDetached() || fragment.getContext() == null) {
                            return;
                        }
                        AndroidUtilities.showSimpleAlert(e.getDataContext(), R.string.error,
                                                         ioException.getLocalizedMessage());
                    });
                }

                ProgressManager.getInstance().runLater(() -> {
                    if (fragment.isDetached() || fragment.getContext() == null) {
                        return;
                    }
                    refreshTreeView(currentNode, fragment.getTreeView());
                });

            });

        });
        dialog.show();

    }

}