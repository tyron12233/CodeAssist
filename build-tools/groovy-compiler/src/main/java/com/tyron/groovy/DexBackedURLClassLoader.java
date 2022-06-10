package com.tyron.groovy;

import com.google.common.hash.HashCode;
import org.gradle.internal.classloader.AppDataDirGuesser;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.hash.DefaultFileHasher;
import org.gradle.internal.hash.DefaultStreamHasher;
import org.gradle.internal.hash.FileHasher;
import org.gradle.util.internal.GFileUtils;
import com.tyron.common.TestUtil;

import org.apache.commons.io.FilenameUtils;
import org.codehaus.groovy.reflection.android.AndroidSupport;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

public class DexBackedURLClassLoader extends URLClassLoader {

    private static final Method ADD_DEX_PATH_METHOD;

    static {
        try {
            ADD_DEX_PATH_METHOD = BaseDexClassLoader.class.getDeclaredMethod("addDexPath", String.class);
            ADD_DEX_PATH_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("System API Changed!");
        }
    }
    private final DexClassLoader dexClassLoader;
    private final FileHasher hasher = new DefaultFileHasher(new DefaultStreamHasher());

    private final Set<URL> loadedUrls = new HashSet<>();

    public DexBackedURLClassLoader(ClassLoader parent) {
        this("", parent, ClassPath.EMPTY);
    }

    public DexBackedURLClassLoader(String name, ClassLoader parent, ClassPath classPath) {
        super(new URL[0], parent);

        if (AndroidSupport.isRunningAndroid()) {
            dexClassLoader = new DexClassLoader("", null, null, parent);
        } else {
            dexClassLoader = null;
        }

        for (URL url : classPath.getAsURLs()) {
            if (url == null) {
                continue;
            }
            addURL(url);
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (AndroidSupport.isRunningAndroid()) {
            return dexClassLoader.loadClass(name);
        } else {
            return getParent().loadClass(name);
        }
    }

    @Nullable
    @Override
    public URL getResource(String name) {
        if (AndroidSupport.isRunningAndroid()) {
            return dexClassLoader.getResource(name);
        } else {
            return getParent().getResource(name);
        }
    }


    @Override
    protected void addURL(URL url) {
        if (!AndroidSupport.isRunningAndroid()) {
            try {
                Method addUrl = getParent().getClass().getMethod("addUrl", URL.class);
                addUrl.setAccessible(true);
                addUrl.invoke(getParent(), url);
            } catch (ReflectiveOperationException ignored) {

            }
            return;
        }

        if (loadedUrls.contains(url)) {
            // already loaded
            return;
        }

        File dexCache = new File(new AppDataDirGuesser().guess(), "dexCache");
        String filePath = url.getFile();
        File file = new File(filePath);

        HashCode hashCode = hasher.hash(file);
        File jarDir = new File(dexCache, hashCode.toString());
        if (!jarDir.exists()) {
            GFileUtils.mkdirs(jarDir);
            ScriptFactory.dexJar(file, jarDir);
        }

        File[] dexFiles = jarDir.listFiles(c -> c.getName().endsWith(".dex"));
        if (dexFiles != null) {
            for (File dexFile : dexFiles) {
                addDexPath(dexFile.getAbsolutePath());
            }
        }
        loadedUrls.add(url);
    }

    public void addDexPath(String path) {
        try {
            ADD_DEX_PATH_METHOD.invoke(dexClassLoader, path);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public void addDexPath(String path, boolean trusted) {
        addDexPath(path);
    }
}
