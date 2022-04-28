package com.tyron.builder.caching.internal.packaging.impl;

import com.google.common.base.CharMatcher;
import com.tyron.builder.internal.file.FilePathUtil;

import java.util.ArrayDeque;
import java.util.Deque;

public class RelativePathParser {
    private static final CharMatcher IS_SLASH = CharMatcher.is('/');

    private final Deque<String> directoryPaths = new ArrayDeque<>();
    private final Deque<String> directoryNames = new ArrayDeque<>();
    private final int rootLength;
    private String currentPath;
    private int sizeOfCommonPrefix;

    public RelativePathParser(String rootPath) {
        this.directoryPaths.addLast(rootPath.substring(0, rootPath.length() - 1));
        this.rootLength = rootPath.length();
        this.currentPath = rootPath;
    }

    public String getRelativePath() {
        return currentPath.substring(rootLength);
    }

    public String getName() {
        return currentPath.substring(sizeOfCommonPrefix + 1);
    }

    public boolean nextPath(String nextPath, boolean directory, Runnable exitDirectoryHandler) {
        currentPath = directory ? nextPath.substring(0, nextPath.length() - 1) : nextPath;
        String lastDirPath = directoryPaths.peekLast();
        sizeOfCommonPrefix = FilePathUtil.sizeOfCommonPrefix(lastDirPath, currentPath, 0, '/');
        int directoriesExited = determineDirectoriesExited(lastDirPath, sizeOfCommonPrefix);
        for (int i = 0; i < directoriesExited; i++) {
            if (exitDirectory(exitDirectoryHandler)) {
                return true;
            }
        }
        String currentName = currentPath.substring(sizeOfCommonPrefix + 1);
        if (directory) {
            directoryPaths.addLast(currentPath);
            directoryNames.addLast(currentName);
        }
        return isRoot();
    }

    private boolean exitDirectory(Runnable exitDirectoryHandler) {
        if (directoryPaths.pollLast() == null) {
            return true;
        }
        if (directoryNames.pollLast() == null) {
            return true;
        }
        exitDirectoryHandler.run();
        return false;
    }

    private static int determineDirectoriesExited(String lastDirPath, int sizeOfCommonPrefix) {
        if (sizeOfCommonPrefix == lastDirPath.length()) {
            return 0;
        }
        int rootDirAdjustment = (sizeOfCommonPrefix == 0) ? 1 : 0;
        return rootDirAdjustment + IS_SLASH.countIn(lastDirPath.substring(sizeOfCommonPrefix));
    }

    public boolean isRoot() {
        return directoryNames.isEmpty() && currentPath.length() == rootLength;
    }

    public void exitToRoot(Runnable exitDirectoryHandler) {
        while (true) {
            if (exitDirectory(exitDirectoryHandler)) {
                break;
            }
        }
    }
}