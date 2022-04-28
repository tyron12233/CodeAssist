package com.tyron.code.ui.file.action.kotlin;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.builder.project.api.KotlinModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.kotlin.KotlinAbstractClassTemplate;
import com.tyron.code.template.kotlin.KotlinClassTemplate;
import com.tyron.code.template.kotlin.KotlinInterfaceTemplate;
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

public class CreateKotlinClassAction extends FileAction {

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.menu_action_new_kotlin_class);
    }

    @Override
    public boolean isApplicable(File file) {
        if (file.isDirectory()) {
            return ProjectUtils.getPackageName(file) != null;
        }
        return false;
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        TreeFileManagerFragment fragment = (TreeFileManagerFragment) e.getData(CommonDataKeys.FRAGMENT);
        TreeView<TreeFile> treeView = fragment.getTreeView();
        TreeNode<TreeFile> currentNode = e.getData(CommonFileKeys.TREE_NODE);
        CreateClassDialogFragment dialogFragment =
                CreateClassDialogFragment.newInstance(getTemplates(),
                        Collections.emptyList());
        dialogFragment.show(fragment.getChildFragmentManager(), null);
        dialogFragment.setOnClassCreatedListener((className, template) -> {
            try {
                File createdFile = ProjectManager.createClass(
                        currentNode.getContent().getFile(),
                        className, template
                );
                TreeNode<TreeFile> newNode = new TreeNode<>(
                        TreeFile.fromFile(createdFile),
                        currentNode.getLevel() + 1
                );

                treeView.addNode(currentNode, newNode);
                treeView.refreshTreeView();
                FileEditorManagerImpl.getInstance().openFile(fragment.requireContext(),
                        createdFile,
                        fileEditor -> fragment.getMainViewModel().openFile(fileEditor));

                Module currentModule = ProjectManager.getInstance()
                        .getCurrentProject()
                        .getModule(currentNode.getContent().getFile());
                if (currentModule instanceof KotlinModule) {
                    ((KotlinModule) currentModule).addKotlinFile(createdFile);
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

    private List<CodeTemplate> getTemplates() {
        return Arrays.asList(
                new KotlinClassTemplate(),
                new KotlinInterfaceTemplate(),
                new KotlinAbstractClassTemplate());
    }
}
