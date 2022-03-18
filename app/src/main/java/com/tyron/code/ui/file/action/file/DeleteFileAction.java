package com.tyron.code.ui.file.action.file;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.builder.project.api.FileManager;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.R;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

import kotlin.io.FileWalkDirection;
import kotlin.io.FilesKt;

public class DeleteFileAction extends FileAction {

    public static final String ID = "fileManagerDeleteFileAction";

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.dialog_delete);
    }

    @Override
    public boolean isApplicable(File file) {
        return true;
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        TreeFileManagerFragment fragment =
                (TreeFileManagerFragment) e.getRequiredData(CommonDataKeys.FRAGMENT);
        TreeView<TreeFile> treeView = fragment.getTreeView();
        TreeNode<TreeFile> currentNode = e.getRequiredData(CommonFileKeys.TREE_NODE);

        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setMessage(String.format(fragment.getString(R.string.dialog_confirm_delete),
                        currentNode.getValue().getFile().getName()))
                .setPositiveButton(fragment.getString(R.string.dialog_delete), (d, which) -> {
                    ProgressManager progress = ProgressManager.getInstance();
                    progress.runNonCancelableAsync(() -> {
                        boolean success = deleteFiles(currentNode, fragment);
                        progress.runLater(() -> {
                            if (fragment.isDetached()) {
                                return;
                            }
                            if (success) {
                                treeView.deleteNode(currentNode);
                                TreeUtil.updateNode(currentNode.getParent());
                                treeView.refreshTreeView();
                                FileEditorManagerImpl.getInstance().closeFile(currentNode.getValue()
                                                                                      .getFile());
                            } else {
                                new MaterialAlertDialogBuilder(fragment.requireContext())
                                        .setTitle(R.string.error)
                                        .setMessage("Failed to delete file.")
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                            }
                        });
                    });

                })
                .show();
    }



    private boolean deleteFiles(TreeNode<TreeFile> currentNode, TreeFileManagerFragment fragment) {
        File currentFile = currentNode.getContent().getFile();
        FilesKt.walk(currentFile, FileWalkDirection.TOP_DOWN).iterator().forEachRemaining(file -> {
            Module module = ProjectManager.getInstance()
                    .getCurrentProject()
                    .getModule(file);

            if (file.getName().endsWith(".java")) {
                // todo: add .kt and .xml checks

                ProgressManager.getInstance().runLater(() ->
                        fragment.getMainViewModel().removeFile(file));

                if (module instanceof JavaModule) {
                    String packageName = StringSearch.packageName(file);
                    if (packageName != null) {
                        packageName += "." + file.getName()
                                .substring(0, file.getName().lastIndexOf("."));
                    }
                    ((JavaModule) module).removeJavaFile(packageName);
                }
            }

            ProgressManager.getInstance().runLater(() -> {
                FileManager fileManager = module.getFileManager();
                if (fileManager.isOpened(file)) {
                    fileManager.closeFileForSnapshot(file);
                }
            });
        });
        try {
            FileUtils.forceDelete(currentFile);
        } catch (IOException e) {
            return false;
        }
        return true;
    }
}
