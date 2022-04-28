package com.tyron.builder.internal.jar;

import com.tyron.builder.project.api.JavaModule;

import org.jetbrains.kotlin.backend.wasm.lower.GenericReturnTypeLowering;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JarArchive {

    private final boolean mVerbose;
    private JarOptions mJarOptions;
    private File mOutputFile;

    public JarArchive(boolean verbose) {
        mVerbose = verbose;
    }

    public void createJarArchive(JavaModule module) throws IOException {

        File classesFolder = new File(module.getBuildDirectory(), "bin/java/classes");
        Manifest manifest = buildManifest(mJarOptions);

        try (FileOutputStream stream = new FileOutputStream(mOutputFile)) {
            try (JarOutputStream out = new JarOutputStream(stream)) {
                File[] children = classesFolder.listFiles();
                if (children != null) {
                    for (File clazz : children) {
                        add(classesFolder.getAbsolutePath(), clazz, out);
                    }
                }
            }
        }
    }

    private void add(String parentPath, File source, JarOutputStream target) throws IOException {
        String name = source.getPath().substring(parentPath.length() + 1);
        if (source.isDirectory()) {
            if (!name.isEmpty()) {
                if (!name.endsWith("/")) {
                    name += "/";
                }

                JarEntry entry = new JarEntry(name);
                entry.setTime(source.lastModified());
                target.putNextEntry(entry);
                target.closeEntry();
            }

            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    add(parentPath, child, target);
                }
            }
            return;
        }

        JarEntry entry = new JarEntry(name);
        entry.setTime(source.lastModified());
        target.putNextEntry(entry);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[1024];
            while (true) {
                int count = in.read(buffer);
                if (count == -1) {
                    break;
                }
                target.write(buffer, 0, count);
            }
            target.closeEntry();
        }
    }
    private Manifest buildManifest(JarOptions options) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (options != null) {
            manifest.getMainAttributes().putAll(options.getAttributes());
        }
        return manifest;
    }

    public void setJarOptions(JarOptions options) {
        mJarOptions = options;
    }

    public void setOutputFile(File output) {
        mOutputFile = output;
    }
}
