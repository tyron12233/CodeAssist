package com.tyron.code.ui.file.tree;

import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class TreeUtil {

    public static final Comparator<File> FILE_FIRST_ORDER = (file1, file2) -> {
        if (file1.isFile() && file2.isDirectory()) {
            return -1;
        } else if (file2.isFile() && file1.isDirectory()) {
            return 1;
        } else {
            return String.CASE_INSENSITIVE_ORDER.compare(file1.getName(), file2.getName());
        }
    };

    /**
     * Get all the tree note at the given root
     */
    public static List<TreeNode<TreeFile>> getNodes(File rootFile) {
        List<TreeNode<TreeFile>> nodes = new ArrayList<>();
        if (rootFile == null) {
            return nodes;
        }

        TreeNode<TreeFile> root = new TreeNode<>(
                TreeFile.fromFile(rootFile), 0
        );
        root.setExpanded(true);

        File[] children = rootFile.listFiles();
        if (children != null) {
            Arrays.sort(children, FILE_FIRST_ORDER);
            for (File file : children) {
                addNode(root, file, 1);
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
