package com.tyron.code.ui.file.tree;

import com.tyron.ui.treeview.TreeNode;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TreeUtil {

    public static final Comparator<File> FILE_FIRST_ORDER = (file1, file2) -> {
        if (file1.isFile() && file2.isDirectory()) {
            return 1;
        } else if (file2.isFile() && file1.isDirectory()) {
            return -1;
        } else {
            return String.CASE_INSENSITIVE_ORDER.compare(file1.getName(), file2.getName());
        }
    };

    public static TreeNode<TreeFile> getRootNode(TreeNode<TreeFile> node) {
        TreeNode<TreeFile> parent = node.getParent();
        TreeNode<TreeFile> root = node;
        while (parent != null) {
            root = parent;
            parent = parent.getParent();
        }
        return root;
    }

    public static void updateNode(TreeNode<TreeFile> node) {
        Set<File> expandedNodes = TreeUtil.getExpandedNodes(node);
        List<TreeNode<TreeFile>> newChildren = getNodes(node.getValue().getFile(), node.getLevel())
                .get(0).getChildren();
        setExpandedNodes(newChildren, expandedNodes);
        node.setChildren(newChildren);
    }

    private static void setExpandedNodes(List<TreeNode<TreeFile>> nodeList, Set<File> expandedNodes) {
        for (TreeNode<TreeFile> treeFileTreeNode : nodeList) {
            if (expandedNodes.contains(treeFileTreeNode.getValue().getFile())) {
                treeFileTreeNode.setExpanded(true);
            }

            setExpandedNodes(treeFileTreeNode.getChildren(), expandedNodes);
        }
    }

    private static Set<File> getExpandedNodes(TreeNode<TreeFile> node) {
        Set<File> expandedNodes = new HashSet<>();
        if (node.isExpanded()) {
            expandedNodes.add(node.getValue().getFile());
        }
        for (TreeNode<TreeFile> child : node.getChildren()) {
            if (child.getValue().getFile().isDirectory()) {
                expandedNodes.addAll(getExpandedNodes(child));
            }
        }
        return expandedNodes;
    }

    public static List<TreeNode<TreeFile>> getNodes(File rootFile) {
        return getNodes(rootFile, 0);
    }

    /**
     * Get all the tree note at the given root
     */
    public static List<TreeNode<TreeFile>> getNodes(File rootFile, int initialLevel) {
        List<TreeNode<TreeFile>> nodes = new ArrayList<>();
        if (rootFile == null) {
            return nodes;
        }

        TreeNode<TreeFile> root = new TreeNode<>(
                TreeFile.fromFile(rootFile), initialLevel
        );
        root.setExpanded(true);

        File[] children = rootFile.listFiles();
        if (children != null) {
            Arrays.sort(children, FILE_FIRST_ORDER);
            for (File file : children) {
                addNode(root, file, initialLevel + 1);
            }
        }
        nodes.add(root);
        return nodes;
    }

    private static void addNode(TreeNode<TreeFile> node, File file, int level) {
        TreeNode<TreeFile> childNode = new TreeNode<>(
                TreeFile.fromFile(file), level
        );

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children, FILE_FIRST_ORDER);
                for (File child : children) {
                    addNode(childNode, child, level + 1);
                }
            }
        }

        node.addChild(childNode);
    }
}
