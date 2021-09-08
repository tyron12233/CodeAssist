package com.tyron.code.util;

import androidx.annotation.Nullable;

import java.io.File;

public class ProjectUtils {

    /**
     * Utility method to get package name from a current directory, this traverses up
     * the file hierarchy until it reaches the "java" folder we can then workout the possible
     * package name from that
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
        String javaPath =directory.getAbsolutePath();

        String cutPath = originalPath.replace(javaPath, "");
        return formatPackageName(cutPath);
    }

    /**
     * Utility method to determine if the folder is the app/src/main/java folder
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
     * Formats a path into a package name
     * eg. com/my/test into com.my.test
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
}
