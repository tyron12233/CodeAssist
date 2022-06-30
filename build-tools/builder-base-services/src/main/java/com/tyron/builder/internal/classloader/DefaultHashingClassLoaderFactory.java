package com.tyron.builder.internal.classloader;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.classpath.DefaultClassPath;
import com.tyron.builder.internal.hash.Hashes;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class DefaultHashingClassLoaderFactory extends DefaultClassLoaderFactory implements HashingClassLoaderFactory {
    private final ClasspathHasher classpathHasher;
    private final Map<ClassLoader, HashCode> hashCodes = Collections.synchronizedMap(new WeakHashMap<ClassLoader, HashCode>());

    public DefaultHashingClassLoaderFactory(ClasspathHasher classpathHasher) {
        this.classpathHasher = classpathHasher;
    }

    @Override
    protected ClassLoader doCreateClassLoader(String name, ClassLoader parent, ClassPath classPath) {
        ClassLoader classLoader = super.doCreateClassLoader(name, parent, classPath);
        hashCodes.put(classLoader, calculateClassLoaderHash(classPath));
        return classLoader;
    }

    @Override
    protected ClassLoader doCreateFilteringClassLoader(ClassLoader parent, FilteringClassLoader.Spec spec) {
        ClassLoader classLoader = super.doCreateFilteringClassLoader(parent, spec);
        hashCodes.put(classLoader, calculateFilterSpecHash(spec));
        return classLoader;
    }

    @Override
    public ClassLoader createChildClassLoader(String name, ClassLoader parent, ClassPath classPath, HashCode implementationHash) {
        HashCode hashCode = implementationHash != null
            ? implementationHash
            : calculateClassLoaderHash(classPath);
        ClassLoader classLoader = super.doCreateClassLoader(name, parent, classPath);
        hashCodes.put(classLoader, hashCode);
        return classLoader;
    }

    @Override
    public HashCode getClassLoaderClasspathHash(ClassLoader classLoader) {
        if (classLoader instanceof ImplementationHashAware) {
            ImplementationHashAware loader = (ImplementationHashAware) classLoader;
            return loader.getImplementationHash();
        }
        if (classLoader.getClass().getName().startsWith("dalvik.system.PathClassLoader")) {
            String s = classLoader.toString();
            s = s.substring("dalvik.system.PathClassLoader[DexPathList[[".length(), s.length() - 3);

            String[] filePaths = s.split(",");
            List<File> files = new ArrayList<>();
            for (String filePath : filePaths) {
                if (filePath.startsWith("dex file ")) {
                    filePath = filePath.substring("dex file ".length());
                }
                if (filePath.startsWith("zip file ")) {
                    filePath = filePath.substring("zip file ".length());
                }
                files.add(new File(filePath.substring(1, filePath.length() - 1)));
            }

            return classpathHasher.hash(DefaultClassPath.of(files));
        }
        return hashCodes.get(classLoader);
    }

    private HashCode calculateClassLoaderHash(ClassPath classPath) {
        return classpathHasher.hash(classPath);
    }

    private static HashCode calculateFilterSpecHash(FilteringClassLoader.Spec spec) {
        Hasher hasher = Hashes.newHasher();
        addToHash(hasher, spec.getClassNames());
        addToHash(hasher, spec.getPackageNames());
        addToHash(hasher, spec.getPackagePrefixes());
        addToHash(hasher, spec.getResourcePrefixes());
        addToHash(hasher, spec.getResourceNames());
        addToHash(hasher, spec.getDisallowedClassNames());
        addToHash(hasher, spec.getDisallowedPackagePrefixes());
        return hasher.hash();
    }

    private static void addToHash(Hasher hasher, Set<String> items) {
        int count = items.size();
        hasher.putInt(count);
        if (count == 0) {
            return;
        }
        String[] sortedItems = items.toArray(new String[count]);
        Arrays.sort(sortedItems);
        for (String item : sortedItems) {
            hasher.putString(item, StandardCharsets.UTF_8);
        }
    }
}
