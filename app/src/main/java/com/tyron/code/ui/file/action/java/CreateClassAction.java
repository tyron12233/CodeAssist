package com.tyron.code.ui.file.action.java;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.java.AbstractTemplate;
import com.tyron.code.template.java.InterfaceTemplate;
import com.tyron.code.template.java.JavaClassTemplate;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.dialog.CreateClassDialogFragment;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.ProjectUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CreateClassAction extends FileAction {

    @Override
    public boolean isApplicable(File file) {
        if (file.isDirectory()) {
            return ProjectUtils.getPackageName(file) != null;
        }
        return false;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.menu_action_new_java_class);
    }

    private List<CodeTemplate> getTemplates() {
        return Arrays.asList(
                new JavaClassTemplate(),
                new AbstractTemplate(),
                new InterfaceTemplate());
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {

        TreeFileManagerFragment fragment = (TreeFileManagerFragment) e.getData(CommonDataKeys.FRAGMENT);
        TreeView<TreeFile> treeView = fragment.getTreeView();
        File file = e.getData(CommonDataKeys.FILE);
        TreeNode<TreeFile> treeNode = e.getData(CommonFileKeys.TREE_NODE);

        CreateClassDialogFragment dialogFragment =
                CreateClassDialogFragment.newInstance(getTemplates(),
                        Collections.emptyList());
        dialogFragment.show(fragment.getChildFragmentManager(), null);
        dialogFragment.setOnClassCreatedListener((className, template) -> {
            try {
                File createdFile = ProjectManager.createClass(
                        file,
                        className, template);
                TreeNode<TreeFile> newNode = new TreeNode<>(
                        TreeFile.fromFile(createdFile),
                        treeNode.getLevel() + 1
                );

                if (createdFile == null) {
                    throw new IOException("Unable to create file");
                }

                treeView.addNode(treeNode, newNode);
                treeView.refreshTreeView();
                FileEditorManagerImpl.getInstance().openFile(fragment.requireContext(),
                        createdFile,
                        fileEditor -> fragment.getMainViewModel().openFile(fileEditor));

                Module currentModule = ProjectManager.getInstance()
                        .getCurrentProject()
                        .getModule(treeNode.getContent().getFile());
                if (currentModule instanceof JavaModule) {
                    ((JavaModule) currentModule).addJavaFile(createdFile);
                }
            } catch (IOException exception) {
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setMessage(exception.getMessage())
                        .setPositiveButton(android.R.string.ok, null)
                        .setTitle(R.string.error)
                        .show();
            }
        });
    }
}
