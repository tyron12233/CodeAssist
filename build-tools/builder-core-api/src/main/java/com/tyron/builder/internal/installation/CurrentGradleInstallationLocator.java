package com.tyron.builder.internal.installation;

import com.tyron.builder.internal.classloader.ClasspathUtil;

import java.io.File;

abstract class CurrentGradleInstallationLocator {

    private static final String BEACON_CLASS_NAME = "org.gradle.internal.installation.beacon.InstallationBeacon";

    private CurrentGradleInstallationLocator() {
    }

    public synchronized static CurrentGradleInstallation locate() {
        return locateViaClassLoader(CurrentGradleInstallationLocator.class.getClassLoader());
    }

    private static CurrentGradleInstallation locateViaClassLoader(ClassLoader classLoader) {
        Class<?> clazz;
        try {
            clazz = classLoader.loadClass(BEACON_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            clazz = CurrentGradleInstallationLocator.class;
        }
        return locateViaClass(clazz);
    }

    static CurrentGradleInstallation locateViaClass(Class<?> clazz) {
        File dir = findDistDir(clazz);
        if (dir == null) {
            return new CurrentGradleInstallation(null);
        } else {
            return new CurrentGradleInstallation(new GradleInstallation(dir));
        }
    }

    private static File findDistDir(Class<?> clazz) {
        File codeSource = ClasspathUtil.getClasspathForClass(clazz);
        if (codeSource.isFile()) {
            return determineDistRootDir(codeSource);
        } else {
            // Loaded from a classes dir - assume we're running from the ide or tests
            return null;
        }
    }

    /**
     * Returns the root directory of a distribution based on the code source of a JAR file. The JAR can either sit in the lib or plugins subdirectory. Returns null if distribution doesn't have
     * expected directory layout.
     *
     * The expected directory layout for JARs of a distribution looks as such:
     *
     * dist-root
     * |_ lib
     * |_ plugins
     *
     * @param codeSource Code source of JAR file
     * @return Distribution root directory
     */
    private static File determineDistRootDir(File codeSource) {
        File parentDir = codeSource.getParentFile();

        if (parentDir.getName().equals("lib")) {
            File pluginsDir = new File(parentDir, "plugins");
            return parentDir.isDirectory() && pluginsDir.exists() && pluginsDir.isDirectory() ? parentDir.getParentFile() : null;
        }

        if (parentDir.getName().equals("plugins")) {
            File libDir = parentDir.getParentFile();
            return parentDir.isDirectory() && libDir.exists() && libDir.isDirectory() && libDir.getName().equals("lib") ? libDir.getParentFile() : null;
        }

        return null;
    }

}