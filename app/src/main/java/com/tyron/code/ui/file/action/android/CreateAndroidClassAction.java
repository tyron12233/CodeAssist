package com.tyron.code.ui.file.action.android;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.ProjectManager;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.code.template.android.ActivityTemplate;
import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.file.CreateClassDialogFragment;
import com.tyron.code.ui.file.action.ActionContext;
import com.tyron.code.ui.file.action.java.CreateClassAction;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

public class CreateAndroidClassAction extends CreateClassAction {

    @Override
    public void addMenu(ActionContext context) {
        context.addSubMenu("new", "New")
                .add("Android Class")
                .setOnMenuItemClickListener(item -> {
                    CreateClassDialogFragment fragment = CreateClassDialogFragment.newInstance(
                            Collections.singletonList(new ActivityTemplate()), Collections.emptyList());
                    fragment.show(context.getFragment().getChildFragmentManager(), null);
                    fragment.setOnClassCreatedListener((className, template) -> {
                        try {
                            File createdFile = ProjectManager.createClass(context.getCurrentNode().getContent().getFile(),
                                    className, template);
                            TreeNode<TreeFile> newNode = new TreeNode<>(
                                    TreeFile.fromFile(createdFile),
                                    context.getCurrentNode().getLevel() + 1
                            );

                            context.getTreeView().addNode(context.getCurrentNode(), newNode);
                            context.getTreeView().refreshTreeView();

                            context.getFragment().getMainViewModel()
                                    .addFile(createdFile);

                            Project currentProject = ProjectManager.getInstance().getCurrentProject();
                            if (currentProject instanceof AndroidProject) {
                                ((AndroidProject) currentProject).addJavaFile(createdFile);
                            }
                        } catch (IOException e) {
                            new MaterialAlertDialogBuilder(context.getFragment().requireContext())
                                    .setMessage(e.getMessage())
                                    .setPositiveButton(android.R.string.ok, null)
                                    .setTitle("Error")
                                    .show();
                        }
                    });
                   return true;
                });
    }
}
