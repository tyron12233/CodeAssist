package com.tyron.code.ui.file.action.android;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.template.android.ActivityTemplate;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.action.java.CreateClassAction;
import com.tyron.code.ui.file.dialog.CreateClassDialogFragment;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.project.ProjectManager;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

public class CreateAndroidClassAction extends CreateClassAction {

    @Override
    public String getTitle(Context context) {
        return "Android Class";
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        TreeFileManagerFragment treeFragment = (TreeFileManagerFragment) e.getData(CommonDataKeys.FRAGMENT);
        TreeView<TreeFile> treeView = treeFragment.getTreeView();
        TreeNode<TreeFile> treeNode = e.getData(CommonFileKeys.TREE_NODE);

        CreateClassDialogFragment fragment = CreateClassDialogFragment.newInstance(
                Collections.singletonList(new ActivityTemplate()), Collections.emptyList());
        fragment.show(treeFragment.getChildFragmentManager(), null);
        fragment.setOnClassCreatedListener((className, template) -> {
            try {
                File currentFile = treeNode.getContent().getFile();
                if (currentFile == null) {
                    throw new IOException("Unable to create class");
                }

                File createdFile = ProjectManager.createClass(currentFile,
                        className, template);
                TreeUtil.updateNode(treeNode.getParent());
                treeView.refreshTreeView();

                FileEditorManagerImpl.getInstance().openFile(treeFragment.requireContext(),
                        createdFile,
                        fileEditor -> treeFragment.getMainViewModel().openFile(fileEditor));

                Module currentModule = ProjectManager.getInstance()
                        .getCurrentProject()
                        .getModule(treeNode.getContent().getFile());
                if (currentModule instanceof AndroidModule) {
                    ((AndroidModule) currentModule).addJavaFile(createdFile);
                }
            } catch (IOException exception) {
                new MaterialAlertDialogBuilder(treeFragment.requireContext())
                        .setMessage(exception.getMessage())
                        .setPositiveButton(android.R.string.ok, null)
                        .setTitle("Error")
                        .show();
            }
        });
    }
}
