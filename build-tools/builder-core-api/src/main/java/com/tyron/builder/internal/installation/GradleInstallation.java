package com.tyron.builder.internal.installation;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GradleInstallation {

    public static final FileFilter DIRECTORY_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };

    private final File dir;
    private final List<File> libDirs;

    public GradleInstallation(File dir) {
        this.dir = dir;
        this.libDirs = Collections.unmodifiableList(findLibDirs(dir));
    }

    public File getGradleHome() {
        return dir;
    }

    public List<File> getLibDirs() {
        return libDirs;
    }

    public File getSrcDir() {
        return dir.toPath().resolve("src").toFile();
    }

    private static List<File> findLibDirs(File dir) {
        List<File> libDirAndSubdirs = new ArrayList<File>();
        collectWithSubdirectories(new File(dir, "lib"), libDirAndSubdirs);
        return libDirAndSubdirs;
    }

    private static void collectWithSubdirectories(File root, Collection<File> collection) {
        collection.add(root);
        File[] subDirs = root.listFiles(DIRECTORY_FILTER);
        if (subDirs != null) {
            for (File subdirectory : subDirs) {
                collectWithSubdirectories(subdirectory, collection);
            }
        }
    }

}
