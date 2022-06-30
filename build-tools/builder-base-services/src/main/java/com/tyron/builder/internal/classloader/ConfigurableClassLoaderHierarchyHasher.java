package com.tyron.builder.internal.classloader;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.common.TestUtil;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

public class ConfigurableClassLoaderHierarchyHasher implements ClassLoaderHierarchyHasher {
    private final Map<ClassLoader, byte[]> knownClassLoaders;
    private final HashingClassLoaderFactory classLoaderFactory;

    public ConfigurableClassLoaderHierarchyHasher(Map<ClassLoader, String> knownClassLoaders, HashingClassLoaderFactory classLoaderFactory) {
        this.classLoaderFactory = classLoaderFactory;
        Map<ClassLoader, byte[]> hashes = new WeakHashMap<>();
        for (Map.Entry<ClassLoader, String> entry : knownClassLoaders.entrySet()) {
            hashes.put(entry.getKey(), entry.getValue().getBytes(Charsets.UTF_8));
        }
        this.knownClassLoaders = hashes;
    }

    @Nullable
    @Override
    public HashCode getClassLoaderHash(ClassLoader classLoader) {
        Visitor visitor = new Visitor();
        visitor.visit(classLoader);
        return visitor.getHash();
    }

    private class Visitor extends ClassLoaderVisitor {
        private final Hasher hasher = Hashing.md5().newHasher();
        private boolean foundUnknown;

        @Override
        public void visit(ClassLoader classLoader) {
            if (addToHash(classLoader)) {
                super.visit(classLoader);
            }
        }

        public HashCode getHash() {
            return foundUnknown ? null : hasher.hash();
        }

        private boolean addToHash(ClassLoader cl) {
            byte[] knownId = knownClassLoaders.get(cl);
            if (knownId != null) {
                hasher.putBytes(knownId);
                return false;
            }

            if (TestUtil.isDalvik() && cl.getClass().getName().equals("java.lang.BootClassLoader")) {
                return false;
            }

            if (cl instanceof CachingClassLoader || cl instanceof MultiParentClassLoader) {
                return true;
            }
            HashCode hash = classLoaderFactory.getClassLoaderClasspathHash(cl);
            if (hash != null) {
                Hashes.putHash(hasher, hash);
                return true;
            }
            foundUnknown = true;
            return false;
        }
    }
}