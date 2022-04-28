package com.tyron.code.ui.file.action.xml;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.xml.LayoutTemplate;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.RegexReason;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.dialog.CreateClassDialogFragment;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class CreateLayoutAction extends FileAction {

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.menu_new_layout);
    }

    @Override
    public boolean isApplicable(File file) {
        if (file.isDirectory()) {
            return ProjectUtils.isResourceXMLDir(file) && file.getName().startsWith("layout");
        }
        return false;
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        TreeFileManagerFragment fragment =
                (TreeFileManagerFragment) e.getRequiredData(CommonDataKeys.FRAGMENT);
        TreeView<TreeFile> treeView = fragment.getTreeView();
        TreeNode<TreeFile> currentNode = e.getRequiredData(CommonFileKeys.TREE_NODE);

        CreateClassDialogFragment dialogFragment =
                CreateClassDialogFragment.newInstance(getTemplates(),
                        Collections.singletonList(new RegexReason("^[a-z0-9_]+$",
                                fragment.getString(R.string.error_resource_name_restriction))));
        dialogFragment.show(fragment.getChildFragmentManager(), null);
        dialogFragment.setOnClassCreatedListener((className, template) -> {
            try {
                File createdFile = ProjectManager.createFile(currentNode.getContent().getFile(),
                        className, template);

                if (createdFile == null) {
                    throw new IOException(fragment.getString(R.string.error_file_creation));
                }

                TreeNode<TreeFile> newNode = new TreeNode<>(TreeFile.fromFile(createdFile),
                        currentNode.getLevel() + 1);

                treeView.addNode(currentNode, newNode);
                treeView.refreshTreeView();
                FileEditorManagerImpl.getInstance().openFile(fragment.requireContext(),
                        createdFile,
                        fileEditor -> fragment.getMainViewModel().openFile(fileEditor));
            } catch (IOException exception) {
                new MaterialAlertDialogBuilder(fragment.requireContext()).setMessage(exception.getMessage()).setPositiveButton(android.R.string.ok, null).setTitle(R.string.error).show();
            }
        });
    }

    private List<CodeTemplate> getTemplates() {
        return Collections.singletonList(new LayoutTemplate());
    }
}
