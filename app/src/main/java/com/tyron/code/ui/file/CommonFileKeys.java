package com.tyron.code.ui.file;

import com.tyron.ui.treeview.TreeNode;
import com.tyron.code.ui.file.tree.model.TreeFile;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

public class CommonFileKeys {

    public static final Key<TreeNode<TreeFile>> TREE_NODE = Key.create("treeFile");
}
