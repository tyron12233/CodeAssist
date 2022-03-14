package com.tyron.code.ui.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.manifest.ManifestMergeTask;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.util.ProjectUtils;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.task.InjectResourcesTask;
import com.tyron.viewbinding.task.InjectViewBindingTask;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import kotlin.collections.CollectionsKt;

public class ProjectManager {

    private static final Logger LOG = IdeLog.getCurrentLogger(ProjectManager.class);

    public interface TaskListener {
        void onTaskStarted(String message);

        void onComplete(Project project, boolean success, String message);
    }

    public interface OnProjectOpenListener {
        void onProjectOpen(Project project);
    }

    private static volatile ProjectManager INSTANCE = null;

    public static synchronized ProjectManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ProjectManager();
        }
        return INSTANCE;
    }

    private final List<OnProjectOpenListener> mProjectOpenListeners = new ArrayList<>();
    private volatile Project mCurrentProject;

    private ProjectManager() {

    }

    public void addOnProjectOpenListener(OnProjectOpenListener listener) {
        if (!CompletionEngine.isIndexing() && mCurrentProject != null) {
            listener.onProjectOpen(mCurrentProject);
        }
        mProjectOpenListeners.add(listener);
    }

    public void removeOnProjectOpenListener(OnProjectOpenListener listener) {
        mProjectOpenListeners.remove(listener);
    }

    public void openProject(Project project,
                            boolean downloadLibs,
                            TaskListener listener,
                            ILogger logger) {
        ProgressManager.getInstance()
                .runNonCancelableAsync(
                        () -> doOpenProject(project, downloadLibs, listener, logger));
    }

    private void doOpenProject(Project project,
                               boolean downloadLibs,
                               TaskListener mListener,
                               ILogger logger) {
        mCurrentProject = project;

        boolean shouldReturn = false;
        // Index the project after downloading dependencies so it will get added to classpath
        try {
            mCurrentProject.open();
        } catch (IOException exception) {
            logger.warning("Failed to open project: " + exception.getMessage());
            shouldReturn = true;
        }
        mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentProject));

        if (shouldReturn) {
            mListener.onComplete(project, false, "Failed to open project.");
            return;
        }

        try {
            mCurrentProject.setIndexing(true);
            mCurrentProject.index();
        } catch (IOException exception) {
            logger.warning("Failed to open project: " + exception.getMessage());
        }

        Module module = mCurrentProject.getMainModule();

        if (module instanceof JavaModule) {
            JavaModule javaModule = (JavaModule) module;
            try {
                downloadLibraries(javaModule, mListener, logger);
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }


        if (module instanceof AndroidModule) {
            mListener.onTaskStarted("Generating resource files.");

            ManifestMergeTask manifestMergeTask =
                    new ManifestMergeTask(project, (AndroidModule) module, logger);
            IncrementalAapt2Task task =
                    new IncrementalAapt2Task(project, (AndroidModule) module, logger, false);
            try {
                manifestMergeTask.prepare(BuildType.DEBUG);
                manifestMergeTask.run();

                task.prepare(BuildType.DEBUG);
                task.run();
            } catch (IOException | CompilationFailedException e) {
                logger.warning("Unable to generate resource classes " + e.getMessage());
            }
        }

        if (module instanceof JavaModule) {
            if (module instanceof AndroidModule) {
                mListener.onTaskStarted("Indexing XML files.");

                XmlIndexProvider index = CompilerService.getInstance()
                        .getIndex(XmlIndexProvider.KEY);
                index.clear();

                XmlRepository xmlRepository = index.get(project, module);
                try {
                    xmlRepository.initialize((AndroidModule) module);
                } catch (IOException e) {
                    String message = "Unable to initialize resource repository. " +
                                     "Resource code completion might be incomplete or unavailable. \n" +
                                     "Reason: " + e.getMessage();
                    LOG.warning(message);
                }
            }

            mListener.onTaskStarted("Indexing");
            try {
                JavaCompilerProvider provider = CompilerService.getInstance()
                        .getIndex(JavaCompilerProvider.KEY);
                JavaCompilerService service = provider.get(project, module);

                if (module instanceof AndroidModule) {
                    InjectResourcesTask.inject(project, (AndroidModule) module);
                    InjectViewBindingTask.inject(project, (AndroidModule) module);
                }

                JavaModule javaModule = ((JavaModule) module);
                Collection<File> files = javaModule.getJavaFiles().values();
                File first = CollectionsKt.firstOrNull(files);
                if (first != null) {
                    service.compile(first.toPath());
                }
            } catch (Throwable e) {
                String message =
                        "Failure indexing project.\n" + Throwables.getStackTraceAsString(e);
                mListener.onComplete(project, false, message);
            }
        }

        mCurrentProject.setIndexing(false);
        mListener.onComplete(project, true, "Index successful");
    }

    private void downloadLibraries(JavaModule project,
                                   TaskListener listener,
                                   ILogger logger) throws IOException {
        DependencyManager manager = new DependencyManager(project,
                                                          ApplicationLoader.applicationContext.getExternalFilesDir(
                                                                  "cache"));
        manager.resolve(project, listener, logger);
    }

    public void closeProject(@NonNull Project project) {
        if (project.equals(mCurrentProject)) {
            mCurrentProject = null;
        }
    }

    public synchronized Project getCurrentProject() {
        return mCurrentProject;
    }

    public static File createFile(File directory,
                                  String name,
                                  CodeTemplate template) throws IOException {
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

    @Nullable
    public static File createClass(File directory,
                                   String className,
                                   CodeTemplate template) throws IOException {
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
