package com.tyron.code.ui.file.action;

import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;

public class ActionContext {

    private final TreeFileManagerFragment mFragment;

    private final TreeView<TreeFile> mTreeView;

    private final TreeNode<TreeFile> mCurrentNode;

    public ActionContext(TreeFileManagerFragment mFragment, TreeView<TreeFile> mTreeView, TreeNode<TreeFile> mCurrentNode) {
        this.mFragment = mFragment;
        this.mTreeView = mTreeView;
        this.mCurrentNode = mCurrentNode;
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
}
