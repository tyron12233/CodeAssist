package tellh.com.recyclertreeview_lib;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by tlh on 2016/10/1 :)
 */

public class TreeNode<T extends LayoutItemType> implements Cloneable {
    private T content;
    private TreeNode parent;
    private List<TreeNode> childList;
    private boolean isExpand;
    private boolean isLocked;
    //the tree high
    private int height = UNDEFINE;

    private static final int UNDEFINE = -1;

    public TreeNode(@NonNull T content) {
        this.content = content;
        this.childList = new ArrayList<>();
    }

    public int getHeight() {
        if (isRoot())
            height = 0;
        else if (height == UNDEFINE)
            height = parent.getHeight() + 1;
        return height;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public boolean isLeaf() {
        return childList == null || childList.isEmpty();
    }

    public void setContent(T content) {
        this.content = content;
    }

    public T getContent() {
        return content;
    }

    public List<TreeNode> getChildList() {
        return childList;
    }

    public void setChildList(List<TreeNode> childList) {
        this.childList.clear();
        for (TreeNode treeNode : childList) {
            addChild(treeNode);
        }
    }

    public TreeNode addChild(TreeNode node) {
        if (childList == null)
            childList = new ArrayList<>();
        childList.add(node);
        node.parent = this;
        return this;
    }

    public boolean toggle() {
        isExpand = !isExpand;
        return isExpand;
    }

    public void collapse() {
        if (isExpand) {
            isExpand = false;
        }
    }

    public void collapseAll() {
        if (childList == null || childList.isEmpty()) {
            return;
        }
        for (TreeNode child : this.childList) {
            child.collapseAll();
        }
    }

    public void expand() {
        if (!isExpand) {
            isExpand = true;
        }
    }

    public void expandAll() {
        expand();
        if (childList == null || childList.isEmpty()) {
            return;
        }
        for (TreeNode child : this.childList) {
            child.expandAll();
        }
    }

    public boolean isExpand() {
        return isExpand;
    }

    public void setParent(TreeNode parent) {
        this.parent = parent;
    }

    public TreeNode getParent() {
        return parent;
    }

    public TreeNode<T> lock() {
        isLocked = true;
        return this;
    }

    public TreeNode<T> unlock() {
        isLocked = false;
        return this;
    }

    public boolean isLocked() {
        return isLocked;
    }

    @Override
    public String toString() {
        return "TreeNode{" +
                "content=" + this.content +
                ", parent=" + (parent == null ? "null" : parent.getContent().toString()) +
                ", childList=" + (childList == null ? "null" : childList.toString()) +
                ", isExpand=" + isExpand +
                '}';
    }

    @Override
    protected TreeNode<T> clone() throws CloneNotSupportedException {
        TreeNode<T> clone = new TreeNode<>(this.content);
        clone.isExpand = this.isExpand;
        return clone;
    }
}
