package com.tyron.code.ui.file.action.xml;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.ui.editor.api.FileEditorManager;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.xml.LayoutTemplate;
import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.file.dialog.CreateClassDialogFragment;
import com.tyron.code.ui.file.RegexReason;
import com.tyron.code.ui.file.action.ActionContext;
import com.tyron.code.ui.file.action.FileAction;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.util.ProjectUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class CreateLayoutAction extends FileAction {

    @Override
    public boolean isApplicable(File file) {
        if (file.isDirectory()) {
            return ProjectUtils.isResourceXMLDir(file) && file.getName().startsWith("layout");
        }
        return false;
    }

    @Override
    public void addMenu(ActionContext context) {
        context.addSubMenu("new", context.getFragment().getString(R.string.menu_new))
                .add("Create layout")
                .setOnMenuItemClickListener(item -> {
                    CreateClassDialogFragment dialogFragment =
                            CreateClassDialogFragment.newInstance(getTemplates(),
                                    Collections.singletonList(new RegexReason("^[a-z0-9_]+$",
                                            context.getFragment().getString(R.string.error_resource_name_restriction))));
                    dialogFragment.show(context.getFragment().getChildFragmentManager(), null);
                    dialogFragment.setOnClassCreatedListener((className, template) -> {
                        try {
                            File createdFile = ProjectManager.createFile(
                                    context.getCurrentNode().getContent().getFile(),
                                    className,
                                    template
                            );

                            TreeNode<TreeFile> newNode = new TreeNode<>(
                                    TreeFile.fromFile(createdFile),
                                    context.getCurrentNode().getLevel() + 1
                            );

                            context.getTreeView().addNode(context.getCurrentNode(), newNode);
                            context.getTreeView().refreshTreeView();
                            FileEditorManager.getInstance().openFile(context.getFragment().requireContext(),
                                    createdFile,
                                    fileEditor -> context.getFragment().getMainViewModel().openFile(fileEditor));
                        } catch (IOException e) {
                            new MaterialAlertDialogBuilder(context.getFragment().requireContext())
                                    .setMessage(e.getMessage())
                                    .setPositiveButton(android.R.string.ok, null)
                                    .setTitle(R.string.error)
                                    .show();
                        }
                    });

                    return true;
                });
    }

    private List<CodeTemplate> getTemplates() {
        return Collections.singletonList(new LayoutTemplate());
    }
}
