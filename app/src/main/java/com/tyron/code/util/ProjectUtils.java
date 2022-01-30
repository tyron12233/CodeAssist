package com.tyron.code.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.ui.treeview.TreeNode;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;

public class ProjectUtils {

    /**
     * Utility method to get package name from a current directory, this traverses up
     * the file hierarchy until it reaches the "java" folder we can then workout the possible
     * package name from that
     *
     * @param directory the parent file of the class
     * @return null if name cannot be determined
     */
    @Nullable
    public static String getPackageName(File directory) {
        if (!directory.isDirectory()) {
            return null;
        }

        File original = directory;

        while (!isJavaFolder(directory)) {
            if (directory == null) {
                return null;
            }
            directory = directory.getParentFile();
        }

        String originalPath = original.getAbsolutePath();
        String javaPath = directory.getAbsolutePath();

        String cutPath = originalPath.replace(javaPath, "");
        return formatPackageName(cutPath);
    }

    /**
     * Utility method to determine if the folder is the app/src/main/java folder
     *
     * @param file file to check
     * @return true if its the java folder
     */
    private static boolean isJavaFolder(File file) {
        if (file == null) {
            return false;
        }
        if (!file.isDirectory()) {
            return false;
        }

        if (file.getName().equals("java")) {
            File parent = file.getParentFile();
            if (parent == null) {
                return false;
            } else return parent.getName().equals("main");
        }

        return false;
    }

    /**
     * Gets the parent directory of a node, if the node is already a directory then
     * it is returned
     *
     * @param node the node to search
     * @return parent directory or itself if its already a directory
     */
    public static File getDirectory(TreeNode<TreeFile> node) {
        File file = node.getContent().getFile();
        if (file.isDirectory()) {
            return file;
        } else {
            return file.getParentFile();
        }
    }

    /**
     * Formats a path into a package name
     * eg. com/my/test into com.my.test
     *
     * @param path input path
     * @return formatted package name
     */
    private static String formatPackageName(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path.replace("/", ".");
    }

    public static boolean isResourceXMLDir(File dir) {
        if (dir == null) {
            return false;
        }
        File parent = dir.getParentFile();
        if (parent != null) {
            return parent.getName().equals("res");
        }
        return false;
    }

    public static boolean isResourceXMLFile(@NonNull File file) {
        if (!file.getName().endsWith(".xml")) {
            return false;
        }
        return isResourceXMLDir(file.getParentFile());
    }

    public static boolean isLayoutXMLFile(@NonNull File file) {
        if (!file.getName().endsWith(".xml")) {
            return false;
        }

        if (file.getParentFile() != null) {
            File parent = file.getParentFile();
            if (parent.isDirectory() && parent.getName().startsWith("layout")) {
                return isResourceXMLFile(file);
            }
        }

        return false;
    }
}
