package com.tyron.groovy;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.origin.Origin;
import com.google.common.hash.HashCode;

import org.gradle.api.GradleException;
import org.gradle.internal.classloader.AppDataDirGuesser;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.hash.DefaultFileHasher;
import org.gradle.internal.hash.DefaultStreamHasher;
import org.gradle.internal.hash.FileHasher;

import org.gradle.internal.hash.Hashes;
import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import groovyjarjarasm.asm.ClassReader;

public class DexBackedURLClassLoader extends DexClassLoader {

    private static final Method ADD_DEX_PATH_METHOD;

    private static final Set<String> PARENT_FIRST = new HashSet<>();

    static {
        try {
            ADD_DEX_PATH_METHOD = BaseDexClassLoader.class.getDeclaredMethod("addDexPath", String.class, Boolean.TYPE);
            ADD_DEX_PATH_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("System API Changed!");
        }

        PARENT_FIRST.add(DexBackedURLClassLoader.class.getName());
        PARENT_FIRST.add("java.lang.management.ManagementFactory");
        PARENT_FIRST.add("java.lang.management.GarbageCollectorMXBean");
        PARENT_FIRST.add("java.lang.management.MemoryManagerMXBean");
        PARENT_FIRST.add("java.lang.management.MemoryMXBean");
        PARENT_FIRST.add("java.lang.management.ClassLoadingMXBean");
        PARENT_FIRST.add("java.lang.management.MemoryUsage");
        PARENT_FIRST.add("java.lang.management.ThreadMXBean");
        PARENT_FIRST.add("com.android.bundle.Config$BundleConfig$BundleType");

    }
    private final URLClassLoader fakeClassLoader;
    private final FileHasher hasher = new DefaultFileHasher(new DefaultStreamHasher());

    private final Set<URL> loadedUrls = new HashSet<>();

    public DexBackedURLClassLoader(ClassLoader parent) {
        this("", parent, ClassPath.EMPTY);
    }

    public DexBackedURLClassLoader(String name, ClassLoader parent, ClassPath classPath) {
        super("", null, null, parent);

        fakeClassLoader = new URLClassLoader(classPath.getAsURLArray(), parent);
    }

    @Override
    protected Class<?> findClass(String moduleName, String name) {
        return super.findClass(moduleName, name);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            if (PARENT_FIRST.contains(name) || name.startsWith("java.") || name.startsWith("javax.")) {
                return Class.forName(name);
            }
        } catch (ClassNotFoundException ignored) {

        }
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            // compile the file
            String resourcePath = name.replace('.', '/') + ".class";
            URL resource = fakeClassLoader.getResource(resourcePath);
            // class is not found in the jars
            if (resource == null) {
                throw e;
            }

            String stringResource = resource.toString();
            String jarPath = stringResource.substring(
                    stringResource.indexOf(":") + 1,
                    stringResource.lastIndexOf('!')
            );
            compileJar(jarPath);
            return super.findClass(name);
        }
    }

    public Class<?> defineDexClass(String name, byte[] bytes, int offset, int length) {
        File dexCache = new File(new AppDataDirGuesser().guess(), "dexCache");
        HashCode hashCode = Hashes.hashBytes(bytes);

        File jarDir = new File(dexCache, hashCode.toString());
        if (!jarDir.exists()) {
            GFileUtils.mkdirs(jarDir);

            D8Command.Builder builder = D8Command.builder();
            builder.setMinApiLevel(24);
            builder.addClassProgramData(bytes, Origin.root());
            builder.setOutput(jarDir.toPath(), OutputMode.DexIndexed);
            try {
                D8.run(builder.build());
            } catch (CompilationFailedException e) {
                throw new GradleException(e.getMessage());
            }
        }

        File[] dexFiles = jarDir.listFiles(c -> c.getName().endsWith(".dex"));
        if (dexFiles != null) {
            for (File dexFile : dexFiles) {
                addDexPathPublic(dexFile.getAbsolutePath());
            }
        }

        if (name == null) {
            name = new ClassReader(bytes).getClassName();
        }
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            throw new GradleException(e.getMessage());
        }
    }


    protected void compileJar(String path) {
        File dexCache = new File(new AppDataDirGuesser().guess(), "dexCache");
        File file = new File(URI.create(path).getPath());

        HashCode hashCode = hasher.hash(file);
        File jarDir = new File(dexCache, hashCode.toString());
        if (!jarDir.exists()) {
            GFileUtils.mkdirs(jarDir);
            ScriptFactory.dexJar(file, jarDir);
        }

        File[] dexFiles = jarDir.listFiles(c -> c.getName().endsWith(".dex"));
        if (dexFiles != null) {
            for (File dexFile : dexFiles) {
                addDexPathPublic(dexFile.getAbsolutePath());
            }
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return super.loadClass(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return super.loadClass(name, resolve);
    }

    @Override
    protected URL findResource(String name) {
        URL resource = super.findResource(name);
        if (resource != null) {
            return resource;
        }
        return fakeClassLoader.findResource(name);
    }

    @Nullable
    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }

    public void addDexPathPublic(String path) {
       addDexPathPublic(path, true);
    }

    public void addDexPathPublic(String path, boolean trusted) {
        try {
            addDexToClasspath(new File(path), this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("DiscouragedPrivateApi")
    private void addDexToClasspath(File dex, ClassLoader classLoader) throws Exception {
        Class<?> dexClassLoaderClass = Class.forName(BaseDexClassLoader.class.getName());
        Field pathListField = dexClassLoaderClass.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object pathList = pathListField.get(classLoader);
        Method addDexPath = pathList.getClass().getDeclaredMethod("addDexPath", String.class, File.class);
        addDexPath.setAccessible(true);
        addDexPath.invoke(pathList, dex.getAbsolutePath(), null);
    }
}
