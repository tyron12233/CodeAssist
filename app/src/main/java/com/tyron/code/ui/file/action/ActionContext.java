package com.tyron.code.ui.file.action;

import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.component.tree.TreeView;
import com.tyron.code.ui.file.tree.TreeFileManagerFragment;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.util.HashMap;
import java.util.Map;

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
