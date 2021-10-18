package com.tyron.code.ui.file.action;

import android.view.Menu;

import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.component.tree.TreeView;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;

public class ActionContext {

    private final TreeFileManagerFragment mFragment;

    private final TreeView<TreeFile> mTreeView;

    private final TreeNode<TreeFile> mCurrentNode;

    private final Menu mMenu;

    public ActionContext(TreeFileManagerFragment mFragment, TreeView<TreeFile> mTreeView, TreeNode<TreeFile> mCurrentNode, Menu mMenu) {
        this.mFragment = mFragment;
        this.mTreeView = mTreeView;
        this.mCurrentNode = mCurrentNode;
        this.mMenu = mMenu;
    }

    public TreeFileManagerFragment getFragment() {
        return mFragment;
    }

    public TreeView<TreeFile> getTreeView() {
        return mTreeView;
    }

    public TreeNode<TreeFile> getCurrentNode() {
        return mCurrentNode;
    }

    public Menu getMenu() {
        return mMenu;
    }
}
