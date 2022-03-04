package com.tyron.completion.java;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.index.CompilerProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.JavaCompilerService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaCompilerProvider extends CompilerProvider<JavaCompilerService> {
    public static final String KEY = JavaCompilerProvider.class.getSimpleName();

    @Nullable
    public static JavaCompilerService get(@NonNull Project project,
                                                  @NonNull JavaModule module) {
        Object index = CompilerService.getInstance().getIndex(KEY);
        if (!(index instanceof JavaCompilerProvider)) {
            return null;
        }

        JavaCompilerProvider provider = ((JavaCompilerProvider) index);
        return provider.getCompiler(project, module);
    }

    private volatile JavaCompilerService mProvider;
    private final Set<File> mCachedPaths;

    public JavaCompilerProvider() {
        mCachedPaths = new HashSet<>();
    }

    @Override
    public synchronized JavaCompilerService get(Project project, Module module) {
        if (module instanceof JavaModule) {
            return getCompiler(project, (JavaModule) module);
        }
        return null;
    }

    public void destroy() {
        mCachedPaths.clear();
        mProvider = null;
    }

    public synchronized JavaCompilerService getCompiler(Project project, JavaModule module) {
        List<Module> dependencies = new ArrayList<>();
        if (project != null) {
            dependencies.addAll(project.getDependencies(module));
        }

        Set<File> paths = new HashSet<>();


        for (Module dependency : dependencies) {
            if (dependency instanceof JavaModule) {
                paths.addAll(((JavaModule) dependency).getJavaFiles().values());
                paths.addAll(((JavaModule) dependency).getLibraries());
                paths.addAll(((JavaModule) dependency).getInjectedClasses().values());
            }
        }

        if (mProvider == null || changed(mCachedPaths, paths)) {
            mProvider = new JavaCompilerService(project, paths, Collections.emptySet(),
                                                Collections.emptySet());

            mCachedPaths.clear();
            mCachedPaths.addAll(paths);
            mProvider.setCurrentModule(module);
        }

        return mProvider;
    }

    private synchronized boolean changed(Set<File> oldFiles, Set<File> newFiles) {
        if (oldFiles.size() != newFiles.size()) {
            return true;
        }

        for (File oldFile : oldFiles) {
            if (!newFiles.contains(oldFile)) {
                return true;
            }
        }

        for (File newFile : newFiles) {
            if (!oldFiles.contains(newFile)) {
                return true;
            }
        }

        return false;
    }

    public static File getOrCreateResourceClass(JavaModule module) throws IOException {
        File outputDirectory = new File(module.getBuildDirectory(), "injected/resource");
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create directory " + outputDirectory);
        }

        File classFile = new File(outputDirectory, "R.java");
        if (!classFile.exists() && !classFile.createNewFile()) {
            throw new IOException("Unable to create " + classFile);
        }
        return classFile;
    }

    public void clear() {
        mProvider = null;
    }
}
