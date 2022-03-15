package com.tyron.builder.project.util;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A trie that stores package segments to a node.
 *
 * Example: The package java.lang.String and java.lang.Object will be stored as:
 * <p>
 *     java
 *      |
 *     lang
 *     /  \
 * String Object
 * </p>
 */
public class PackageTrie {

    private final Node mRoot;

    public PackageTrie() {
        mRoot = new Node();
    }

    /**
     * Add the fully qualified name to the index.
     *
     * @param fqn The fully qualified name of a class, including its name
     */
    public void add(@NonNull String fqn) {
        String[] parts = getParts(fqn);
        Node current = mRoot;
        for (String part : parts) {
            current = current.getOrCreateChild(part);
        }
        // this is the end node, so mark it as a leaf
        current.isLeaf = true;
    }

    public List<String> getMatchingPackages(String packageQuery) {
        List<String> result = new ArrayList<>();
        StringBuilder currentPackage = new StringBuilder();
        String[] parts = getParts(packageQuery);
        Node current = mRoot;
        for (String part : parts) {
            if (current == null || current.getChildren() == null || !current.getChildren().containsKey(part)) {
                return result;
            }

            if (current.isLeaf) {
                String fqn = currentPackage.length() > 0
                        ? currentPackage + "." + part
                        : part;
                result.add(fqn);
            } else {
                boolean insertDot = currentPackage.length() > 0;
                if (insertDot) {
                    currentPackage.append('.');
                }
                currentPackage.append(part);
            }

            current = current.getChildren().get(part);
        }

        if (current.getChildren() != null) {
            for (Node node : current.getChildren().values()) {
                recurse(node, currentPackage.toString(), result);
            }
        }

        return result;
    }

    private void recurse(Node node, String currentPackage, List<String> result) {
        if (node.isLeaf) {
            String newResult = currentPackage.length() > 0
                    ? currentPackage + "." + node.getValue()
                    : node.getValue();
            result.add(newResult);
        } else {
            currentPackage = currentPackage.isEmpty()
                    ? node.getValue()
                    : currentPackage + "." + node.getValue();
        }

        if (node.getChildren() == null) {
            return;
        }
        for (Node child : node.getChildren().values()) {
            recurse(child, currentPackage, result);
        }
    }

    private String[] getParts(String fqn) {
        if (fqn.contains(".")) {
            return fqn.split("\\.");
        }
        return new String[]{fqn};
    }

    private static class Node {

        private Map<String, Node> mChildren;

        private boolean isLeaf;

        private String mValue;

        public Node() {

        }

        public Node(String value) {
            mValue = value;
        }

        public Node getOrCreateChild(String part) {
            if (mChildren == null) {
                mChildren = new HashMap<>();
            }

            return mChildren.computeIfAbsent(part, Node::new);
        }

        public Map<String, Node> getChildren() {
            return mChildren;
        }

        public String getValue() {
            return mValue;
        }
    }
}
