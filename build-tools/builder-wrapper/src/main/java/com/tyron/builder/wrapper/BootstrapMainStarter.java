package com.tyron.builder.wrapper;

import java.io.Closeable;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class BootstrapMainStarter {
    public void start(String[] args, File gradleHome) throws Exception {
        File gradleJar = findLauncherJar(gradleHome);
        if (gradleJar == null) {
            throw new RuntimeException(String.format("Could not locate the Gradle launcher JAR in Gradle distribution '%s'.", gradleHome));
        }
        URLClassLoader contextClassLoader = new URLClassLoader(new URL[]{gradleJar.toURI().toURL()}, ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(contextClassLoader);
        Class<?> mainClass = contextClassLoader.loadClass("org.gradle.launcher.GradleMain");
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, new Object[]{args});
        if (contextClassLoader instanceof Closeable) {
            ((Closeable) contextClassLoader).close();
        }
    }

    static File findLauncherJar(File gradleHome) {
        File libDirectory = new File(gradleHome, "lib");
        if (libDirectory.exists() && libDirectory.isDirectory()) {
            File[] launcherJars = libDirectory.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.matches("gradle-launcher-.*\\.jar");
                }
            });
            if (launcherJars!=null && launcherJars.length==1) {
                return launcherJars[0];
            }
        }
        return null;
    }
}
