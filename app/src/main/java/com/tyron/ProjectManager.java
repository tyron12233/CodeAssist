package com.tyron;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Charsets;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.psi.completion.CompletionEnvironment;
import com.tyron.resolver.DependencyDownloader;
import com.tyron.resolver.DependencyResolver;
import com.tyron.resolver.DependencyUtils;
import com.tyron.resolver.model.Dependency;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class ProjectManager {

    public interface TaskListener {
        void onTaskStarted(String message);
        void onComplete(boolean success, String message);
    }

    public interface OnProjectOpenListener {
        void onProjectOpen(Project project);
    }

    private static ProjectManager INSTANCE = null;

    public static ProjectManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ProjectManager();
        }
        return INSTANCE;
    }

    private CompletionEnvironment mCompletionEnvironment;
    private final List<OnProjectOpenListener> mProjectOpenListeners = new ArrayList<>();
    private Project mCurrentProject;

    private ProjectManager() {
        
    }

    public void addOnProjectOpenListener(OnProjectOpenListener listener) {
        mProjectOpenListeners.add(listener);
    }

    public void removeOnProjectOpenListener(OnProjectOpenListener listener) {
        mProjectOpenListeners.remove(listener);
    }

    public void openProject(Project proj, boolean downloadLibs, TaskListener mListener, ILogger logger) {
        Executors.newSingleThreadExecutor().execute(() -> {
            if (downloadLibs) {
                mListener.onTaskStarted("Resolving dependencies");
                // this is the existing libraries from app/libs
                Set<Dependency> libs = new HashSet<>();

                // dependencies parsed from the build.gradle file
                Set<Dependency> dependencies = new HashSet<>();
                try {
                    dependencies.addAll(DependencyUtils.parseGradle(new File(proj.mRoot, "app/build.gradle")));
                } catch (Exception exception) {
                    //TODO: handle parse error
                    mListener.onComplete(false, exception.getMessage());
                }

                DependencyResolver resolver = new DependencyResolver(dependencies, proj.getLibraryDirectory());
                resolver.addResolvedLibraries(libs);
                resolver.setListener(d -> mListener.onTaskStarted("Resolving " + d.toString()));
                dependencies = resolver.resolveMain();

                mListener.onTaskStarted("Downloading dependencies");
                DependencyDownloader downloader = new DependencyDownloader(libs, proj.getLibraryDirectory());
                downloader.setListener(d -> mListener.onTaskStarted("Downloading " + d.toString()));
                try {
                    downloader.cache(dependencies);
                } catch (IOException e) {
                    logger.warning("Unable to download dependencies: " + e.getMessage());
                }
            }
            mListener.onTaskStarted("Indexing");
            try {
                proj.open();
            } catch(IOException e) {
                mListener.onComplete(false, "Unable to open project: " + e.getMessage());
                return;
            }
            mCurrentProject = proj;
            mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentProject));
            //mCompletionEnvironment = CompletionEnvironment.newInstance(proj.getJavaFiles().values(), proj.getKotlinFiles().values(), proj.getLibraries());
            CompletionEngine.getInstance().index(proj, () -> mListener.onComplete(true, "Index successful"));
        });
    }

    public void closeProject(@NonNull Project project) {
        if (project.equals(mCurrentProject)) {
            mCurrentProject = null;
            if (mCompletionEnvironment != null) {
                mCompletionEnvironment.close();
            }
        }
    }

    @Nullable
    public CompletionEnvironment getCompletionEnvironment() {
        return mCompletionEnvironment;
    }

    public Project getCurrentProject() {
        return mCurrentProject;
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
