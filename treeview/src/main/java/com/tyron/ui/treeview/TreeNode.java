/*
 * Copyright 2016 - 2017 ShineM (Xinyuan)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under.
 */

package com.tyron.ui.treeview;

import java.util.ArrayList;
import java.util.List;

import com.tyron.ui.treeview.helper.TreeHelper;

/**
 * Created by xinyuanzhong on 2017/4/20.
 */

public class TreeNode<D> {
    private int level;

    private D value;

    private TreeNode<D> parent;

    private List<TreeNode<D>> children;

    private int index;

    private boolean expanded;

    private boolean selected;

    private boolean itemClickEnable = true;

    public TreeNode(D value, int level) {
        this.value = value;
        this.children = new ArrayList<>();
        setLevel(level);
    }

    public static <D> TreeNode<D> root() {
        return new TreeNode<>(null, 0);
    }

    public static <D> TreeNode<D> root(List<TreeNode<D>> children) {
        TreeNode<D> root = root();
        root.setChildren(children);
        return root;
    }

    public void addChild(TreeNode<D> treeNode) {
        if (treeNode == null) {
            return;
        }
        children.add(treeNode);
        treeNode.setIndex(getChildren().size());
        treeNode.setParent(this);
    }


    public void removeChild(TreeNode<D> treeNode) {
        if (treeNode == null || getChildren().size() < 1) {
            return;
        }
        getChildren().remove(treeNode);
    }

    public boolean isLeaf() {
        return children.size() == 0;
    }

    public boolean isLastChild() {
        if (parent == null) {
            return false;
        }
        List<TreeNode<D>> children = parent.getChildren();
        return children.size() > 0 && children.indexOf(this) == children.size() - 1;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public D getContent() {
        return value;
    }

    public D getValue() {
        return value;
    }

    public void setValue(D value) {
        this.value = value;
    }

    public TreeNode<D> getParent() {
        return parent;
    }

    public void setParent(TreeNode<D> parent) {
        this.parent = parent;
    }

    public List<TreeNode<D>> getChildren() {
        if (children == null) {
            return new ArrayList<>();
        }
        return children;
    }

    public List<TreeNode<D>> getSelectedChildren() {
        List<TreeNode<D>> selectedChildren = new ArrayList<>();
        for (TreeNode<D> child : getChildren()) {
            if (child.isSelected()) {
                selectedChildren.add(child);
            }
        }
        return selectedChildren;
    }
    
    public void setChildren(List<TreeNode<D>> children) {
        if (children == null) {
            return;
        }        
        this.children = new ArrayList<>();
        for (TreeNode<D> child : children) {
            addChild(child);
        }
    }

    /**
     * Updating the list of children while maintaining the tree structure
     */
    public void updateChildren(List<TreeNode<D>> children) {
        List<Boolean> expands = new ArrayList<>();
        List<TreeNode<D>> allNodesPre = TreeHelper.getAllNodes(this);
        for (TreeNode<D> node : allNodesPre) {
            expands.add(node.isExpanded());
        }

        this.children = children;
        List<TreeNode<D>> allNodes = TreeHelper.getAllNodes(this);
        if (allNodes.size() == expands.size()) {
            for (int i = 0; i < allNodes.size(); i++) {
                allNodes.get(i).setExpanded(expands.get(i));
            }
        }
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean hasChild() {
        return children.size() > 0;
    }

    public boolean isItemClickEnable() {
        return itemClickEnable;
    }

    public void setItemClickEnable(boolean itemClickEnable) {
        this.itemClickEnable = itemClickEnable;
    }

    public String getId() {
        return getLevel() + "," + getIndex();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

}
