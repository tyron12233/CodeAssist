package org.gradle.initialization;

import org.gradle.internal.classloader.ClassLoaderFactory;
import org.gradle.internal.classloader.ClasspathUtil;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.jvm.Jvm;

import java.io.File;
import java.net.URLClassLoader;

public class DefaultJdkToolsInitializer implements JdkToolsInitializer {

    private final ClassLoaderFactory classLoaderFactory;

    public DefaultJdkToolsInitializer(ClassLoaderFactory classLoaderFactory) {
        this.classLoaderFactory = classLoaderFactory;
    }

    @Override
    public void initializeJdkTools() {
        // Add in tools.jar to the systemClassloader parent
        File toolsJar = Jvm.current().getToolsJar();
        if (toolsJar != null) {
            final ClassLoader systemClassLoaderParent = classLoaderFactory.getIsolatedSystemClassLoader();
            ClasspathUtil.addUrl((URLClassLoader) systemClassLoaderParent, DefaultClassPath.of(toolsJar).getAsURLs());
        }
    }
}
