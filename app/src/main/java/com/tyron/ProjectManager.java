package com.tyron;

import androidx.annotation.NonNull;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.provider.CompletionEngine;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ProjectManager {

    public interface TaskListener {
        void onTaskStarted(String message);

        void onComplete(Module module, boolean success, String message);
    }

    public interface OnProjectOpenListener {
        void onProjectOpen(Module module);
    }

    private static volatile ProjectManager INSTANCE = null;

    public static synchronized ProjectManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ProjectManager();
        }
        return INSTANCE;
    }

    private final List<OnProjectOpenListener> mProjectOpenListeners = new ArrayList<>();
    private Module mCurrentModule;

    private ProjectManager() {

    }

    public void addOnProjectOpenListener(OnProjectOpenListener listener) {
        if (!CompletionEngine.isIndexing() && mCurrentModule != null) {
            listener.onProjectOpen(mCurrentModule);
        }
        mProjectOpenListeners.add(listener);
    }

    public void removeOnProjectOpenListener(OnProjectOpenListener listener) {
        mProjectOpenListeners.remove(listener);
    }

    public void openProject(Module module,
                            boolean downloadLibs,
                            TaskListener listener,
                            ILogger logger) {
        Executors.newSingleThreadExecutor().execute(() -> {
            doOpenProject(module, downloadLibs, listener, logger);
        });
    }

    private void doOpenProject(Module module,
                               boolean downloadLibs,
                               TaskListener mListener,
                               ILogger logger) {
        try {
            module.open();
        } catch (IOException e) {
            mListener.onComplete(module,false, "Unable to open project: " + e.getMessage());
            return;
        }

        mCurrentModule = module;

        if (module instanceof JavaModule) {
            JavaModule javaProject = (JavaModule) module;
            try {
                downloadLibraries(javaProject, mListener, logger);
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }

        // Index the project after downloading dependencies so it will get added to classpath
        module.index();
        mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentModule));

        if (module instanceof JavaModule) {
            mListener.onTaskStarted("Indexing");
            try {
                CompletionEngine.getInstance().index((JavaModule) module, logger, () ->
                        mListener.onComplete(module, true, "Index successful"));
            } catch (Throwable e) {
                String message = "Failure indexing project.\n" +
                        Throwables.getStackTraceAsString(e);
                mListener.onComplete(module, false, message);
            }
        }
    }

    private void downloadLibraries(JavaModule project, TaskListener listener, ILogger logger) throws IOException {
        DependencyManager manager = new DependencyManager(ApplicationLoader.applicationContext.getExternalFilesDir("cache"));
        manager.resolve(project, listener, logger);
    }

    public void closeProject(@NonNull Module module) {
        if (module.equals(mCurrentModule)) {
            mCurrentModule = null;
        }
    }

    public Module getCurrentProject() {
        return mCurrentModule;
    }

    public static File createFile(File directory, String name, CodeTemplate template) throws IOException {
        if (!directory.isDirectory()) {
            return null;
        }

        String code = template.get()
                .replace(CodeTemplate.CLASS_NAME, name);

        File classFile = new File(directory, name + template.getExtension());
        if (classFile.exists()) {
            return null;
        }
        if (!classFile.createNewFile()) {
            return null;
        }

        FileUtils.writeStringToFile(classFile, code, Charsets.UTF_8);
        return classFile;
    }

    @NonNull
    public static File createClass(File directory, String className, CodeTemplate template) throws IOException {
        if (!directory.isDirectory()) {
            return null;
        }

        String packageName = ProjectUtils.getPackageName(directory);
        if (packageName == null) {
            return null;
        }

        String code = template.get()
                .replace(CodeTemplate.PACKAGE_NAME, packageName)
                .replace(CodeTemplate.CLASS_NAME, className);

        File classFile = new File(directory, className + template.getExtension());
        if (classFile.exists()) {
            return null;
        }
        if (!classFile.createNewFile()) {
            return null;
        }

        FileUtils.writeStringToFile(classFile, code, Charsets.UTF_8);
        return classFile;
    }
}
