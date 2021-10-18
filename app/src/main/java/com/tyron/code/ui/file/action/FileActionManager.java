package com.tyron.code.ui.file.action;

import android.widget.PopupMenu;

import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.file.action.file.DeleteFileAction;
import com.tyron.code.ui.file.action.java.CreateClassAction;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.util.ArrayList;
import java.util.List;

public class FileActionManager {

    private final List<FileAction> mActions = new ArrayList<>();

    public FileActionManager() {
        registerAction(new CreateClassAction());
        registerAction(new DeleteFileAction());
    }

    public void registerAction(FileAction action) {
        mActions.add(action);
    }

    public void addMenus(PopupMenu menu, TreeNode<TreeFile> node, TreeFileManagerFragment fragment) {
        ActionContext context = new ActionContext(fragment, fragment.getTreeView(), node, menu.getMenu());
        addMenus(context);
    }

    private void addMenus(ActionContext context) {
        for (FileAction action : mActions) {
            if (action.isApplicable(context.getCurrentNode().getValue().getFile())) {
                action.addMenu(context);
            }
        }
    }
}
