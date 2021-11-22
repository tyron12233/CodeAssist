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

    private final Menu mMenu;

    private final Map<String, Integer> mIds = new HashMap<>();
    private int mIdCount;

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

    /**
     * Get the an id for a specified key, useful for adding the same menu on
     * an existing submenu
     *
     * @param name Key name for the id
     * @return the id of the menu
     */
    public Integer getMenuId(String name) {
        if (!mIds.containsKey(name)) {
            mIds.put(name, mIdCount++);
        }
        return mIds.get(name);
    }

    /**
     * Add a sub menu, if the sub menu already exists then it will add
     * it to the current one, if not it will create a new sub menu
     *
     * @param name The key to get the id from
     * @param title The title of the menu to be displayed
     * @return The created SubMenu, non-null
     */
    public SubMenu addSubMenu(String name, String title) {
        int id = getMenuId(name);
        MenuItem item = getMenu().findItem(id);
        if (item == null || item.getSubMenu() == null) {
            return getMenu().addSubMenu(id, Menu.NONE, Menu.NONE, title);
        } else {
            return item.getSubMenu();
        }
    }
}
