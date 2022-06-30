package com.tyron.kotlin_completion;

import com.tyron.builder.BuildModule;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.kotlin_completion.classpath.ClassPathEntry;
import com.tyron.kotlin_completion.classpath.DefaultClassPathResolver;
import com.tyron.kotlin_completion.compiler.Compiler;
import com.tyron.kotlin_completion.util.AsyncExecutor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import kotlin.collections.SetsKt;
import kotlin.jvm.functions.Function1;

public class CompilerClassPath implements Closeable {

    private final Set<Path> mWorkspaceRoots = new HashSet<>();
    private final Set<Path> mJavaSourcePath;
    final Set<ClassPathEntry> mClassPath;
    private final AndroidModule mProject;

   // private final CompilerConfiguration mConfiguration;

    private Compiler compiler;

    private final AsyncExecutor asyncExecutor = new AsyncExecutor();

    public CompilerClassPath(AndroidModule project) {
        //mConfiguration = config;
        mProject = project;

        mJavaSourcePath = project.getJavaFiles().values().stream().map(File::toPath).collect(Collectors.toSet());
        mJavaSourcePath.addAll(project.getJavaFiles().values().stream().map(File::toPath).collect(Collectors.toSet()));
        mJavaSourcePath.addAll(project.getResourceClasses().values().stream().map(File::toPath).collect(Collectors.toList()));
        mClassPath = project.getLibraries().stream().map(file -> new ClassPathEntry(file.toPath(), null)).collect(Collectors.toSet());
        mClassPath.add(new ClassPathEntry(BuildModule.getAndroidJar().toPath(), null));

        compiler = new Compiler(project, mJavaSourcePath, mClassPath.stream().map(ClassPathEntry::getCompiledJar).collect(Collectors.toSet()));
        //compiler.updateConfiguration(mConfiguration);
    }

    private boolean refresh(boolean updateClassPath, boolean updateJavaSourcePath) {
        DefaultClassPathResolver resolver = new DefaultClassPathResolver(mProject.getLibraries());
        boolean refreshCompiler = updateJavaSourcePath;

        if (updateClassPath) {
            Set<ClassPathEntry> newClassPath = resolver.getClassPathOrEmpty();
            if (!newClassPath.equals(mClassPath)) {
                synchronized (mClassPath) {
                    syncPaths(mClassPath, newClassPath, "class paths", ClassPathEntry::getCompiledJar);
                }

                refreshCompiler = true;
            }
        }

        asyncExecutor.compute(() -> {
            Set<ClassPathEntry> newClassPathWithSources = resolver.getClassPathWithSources();
            synchronized (mClassPath) {
                syncPaths(mClassPath, newClassPathWithSources, "Source paths", ClassPathEntry::getSourceJar);
            }
            return null;
        });

        if (refreshCompiler) {
            compiler.close();
            compiler = new Compiler(mProject, mJavaSourcePath, mClassPath.stream().map(ClassPathEntry::getCompiledJar).collect(Collectors.toSet()));
            updateCompilerConfiguration();
        }

        return refreshCompiler;
    }

    private void updateCompilerConfiguration() {
        //compiler.updateConfiguration(mConfiguration);
    }

    public <T> void syncPaths(Set<ClassPathEntry> dest, Set<ClassPathEntry> newSet, String name, Function1<T, Path> function) {
        Set<ClassPathEntry> added = SetsKt.minus(newSet, dest);
        Set<ClassPathEntry> removed = SetsKt.minus(dest, newSet);

        dest.removeAll(removed);
        dest.addAll(added);
    }

    public Compiler getCompiler() {
        return compiler;
    }

    @Override
    public void close() throws IOException {

    }
}
