package com.tyron.builder.compiler.java;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.builder.project.api.Module;
import com.tyron.common.util.Cache;
import com.tyron.common.util.StringSearch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyWithDefaultValue;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaTaskTest {

    private JavaProject testProject = new JavaProject() {

        private final Map<String, File> javaFiles = new HashMap<>();

        @NonNull
        @Override
        public Map<String, File> getJavaFiles() {
            return javaFiles;
        }

        @Override
        public File getJavaFile(@NonNull String packageName) {
            return javaFiles.get(packageName);
        }

        @Override
        public void removeJavaFile(@NonNull String packageName) {
            javaFiles.remove(packageName);
        }

        @Override
        public void addJavaFile(@NonNull File javaFile) {
            javaFiles.put(StringSearch.packageName(javaFile), javaFile);
        }

        @Override
        public List<File> getLibraries() {
            return Collections.emptyList();
        }

        @NonNull
        @Override
        public File getResourcesDir() {
            return new File(getRootFile(), "app/src/main/resources");
        }

        @NonNull
        @Override
        public File getJavaDirectory() {
            return new File(getRootFile(), "app/src/main/java");
        }

        @Override
        public ProjectSettings getSettings() {
            return null;
        }

        @Override
        public List<Module> getModules() {
            return null;
        }

        @Override
        public File getRootFile() {
            return new File("/home/tyron/AndroidStudioProjects/CodeAssist/build-logic/src/test/resources/TestProject");
        }

        @Override
        public void open() throws IOException {

        }

        @Override
        public void clear() {

        }

        @Override
        public File getBuildDirectory() {
            return null;
        }

        @Override
        public <T> T putUserDataIfAbsent(@NotNull Key<T> key, @NotNull T t) {
            return null;
        }

        @Override
        public <T> boolean replace(@NotNull Key<T> key, @Nullable T t, @Nullable T t1) {
            return false;
        }

        private final KeyFMap map = KeyFMap.EMPTY_MAP;

        @Override
        public <T> T getUserData(@NotNull Key<T> key) {
            T t = map.get(key);
            if (t == null && key instanceof KeyWithDefaultValue) {
                return ((KeyWithDefaultValue<T>) key).getDefaultValue();
            }
            return t;
        }

        @Override
        public <T> void putUserData(@NotNull Key<T> key, @Nullable T t) {
            map.plus(key, t);
        }
    };

    @Test
    public void testCompile() throws Exception {
        IncrementalJavaTask task = new IncrementalJavaTask(testProject, ILogger.STD_OUT);
        testProject.addJavaFile(new File(testProject.getJavaDirectory(), "com/tyron/test/Test.java"));
        task.prepare(BuildType.RELEASE);
        task.run();
    }
}
