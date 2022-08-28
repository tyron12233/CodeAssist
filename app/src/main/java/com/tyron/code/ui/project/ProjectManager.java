package com.tyron.code.ui.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Charsets;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.ContentRoot;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.ui.editor.log.AppLogFragment;
import com.tyron.code.util.ProjectUtils;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.java.compiler.ParseTask;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.provider.PruneMethodBodies;
import com.tyron.completion.progress.ProgressManager;

import org.apache.commons.io.FileUtils;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProgressListener;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaModuleDependency;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

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

        GradleConnector gradleConnector = GradleConnector.newConnector();
        gradleConnector.forProjectDirectory(mCurrentProject.getRootFile());
        gradleConnector.useDistribution(URI.create("codeAssist"));

        try (ProjectConnection projectConnection = gradleConnector.connect()) {
            mListener.onTaskStarted("Build model");

            AppLogFragment.outputStream.write("\033[H\033[2J".getBytes());

            IdeaProject ideaProject = projectConnection.model(IdeaProject.class)
                    .setStandardError(AppLogFragment.outputStream)
                    .setStandardOutput(AppLogFragment.outputStream)
                    .addProgressListener((org.gradle.tooling.events.ProgressListener) event -> mListener.onTaskStarted(event.getDisplayName()))
                    .get();

            mListener.onTaskStarted("Index model");

            // remove the previous models
            mCurrentProject.clear();
            buildModel(ideaProject, mCurrentProject);
        } catch (Throwable t) {
            mListener.onComplete(mCurrentProject, false, t.getMessage());
            return;
        }

        mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentProject));


//        Module module = mCurrentProject.getMainModule();
//
//        if (module instanceof AndroidModule) {
//            mListener.onTaskStarted("Generating resource files.");
//
//            ManifestMergeTask manifestMergeTask =
//                    new ManifestMergeTask(project, (AndroidModule) module, logger);
//            IncrementalAapt2Task task =
//                    new IncrementalAapt2Task(project, (AndroidModule) module, logger, false);
//            try {
//                manifestMergeTask.prepare(BuildType.DEBUG);
//                manifestMergeTask.run();
//
//                task.prepare(BuildType.DEBUG);
//                task.run();
//            } catch (IOException | CompilationFailedException e) {
//                logger.warning("Unable to generate resource classes " + e.getMessage());
//            }
//        }
//
//        if (module instanceof JavaModule) {
//            if (module instanceof AndroidModule) {
//                mListener.onTaskStarted("Indexing XML files.");
//
//                XmlIndexProvider index = CompilerService.getInstance()
//                        .getIndex(XmlIndexProvider.KEY);
//                index.clear();
//
//                XmlRepository xmlRepository = index.get(project, module);
//                try {
//                    xmlRepository.initialize((AndroidModule) module);
//                } catch (IOException e) {
//                    String message = "Unable to initialize resource repository. " +
//                                     "Resource code completion might be incomplete or unavailable. \n" +
//                                     "Reason: " + e.getMessage();
//                    LOG.warning(message);
//                }
//            }
//
//            mListener.onTaskStarted("Indexing");
//            try {
//                JavaCompilerProvider provider = CompilerService.getInstance()
//                        .getIndex(JavaCompilerProvider.KEY);
//                JavaCompilerService service = provider.get(project, module);
//
//                if (module instanceof AndroidModule) {
//                    InjectResourcesTask.inject(project, (AndroidModule) module);
//                    InjectViewBindingTask.inject(project, (AndroidModule) module);
//                }
//
//                JavaModule javaModule = ((JavaModule) module);
//                Collection<File> files = javaModule.getJavaFiles().values();
//                File first = CollectionsKt.firstOrNull(files);
//                if (first != null) {
//                    service.compile(first.toPath());
//                }
//            } catch (Throwable e) {
//                String message =
//                        "Failure indexing project.\n" + Throwables.getStackTraceAsString(e);
//
//            }

//            mListener.onComplete(project, false, message);
//        }

        mCurrentProject.setIndexing(false);
        mListener.onComplete(project, true, "Index successful");
    }

    private void buildModel(IdeaProject project, Project currentProject) throws IOException {
        List<? extends IdeaModule> allModules = project.getModules().getAll();
        for (IdeaModule ideaModule : allModules) {
            Module module = buildModule(ideaModule);
            currentProject.addModule(module);
            indexModule(module);
        }
    }

    /**
     * Indexes each module so completion would work immediately
     *
     * In-order to keep indexing as fast as possible, method bodies of each classes are removed.
     * When the file is opened in the editor, its contents will be re-parsed with method bodies
     * included.
     */
    private void indexModule(Module module) throws IOException {
        module.open();
        module.index();

        JavaModule javaModule = (JavaModule) module;
        for (File value : javaModule.getJavaFiles().values()) {
            CompilationInfo info = CompilationInfo.get(module.getProject(), value);
            if (info == null) {
                continue;
            }
            info.updateImmediately(new SimpleJavaFileObject(value.toURI(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    Parser parser = Parser.parseFile(module.getProject(), value.toPath());
                    return new PruneMethodBodies(info.impl.getJavacTask())
                            .scan(parser.root, 0L);
                }
            });
        }
    }

    private Module buildModule(IdeaModule module) {
        File projectDirectory = module.getGradleProject().getProjectDirectory();
        AndroidModuleImpl impl = new AndroidModuleImpl(projectDirectory);
        impl.setName(module.getProjectIdentifier().getProjectPath());

        final DomainObjectSet<? extends IdeaContentRoot> contentRoots = module.getContentRoots();
        for (IdeaContentRoot contentRoot : contentRoots) {
            ContentRoot implContentRoot = new ContentRoot(contentRoot.getRootDirectory());

            for (IdeaSourceDirectory sourceDirectory : contentRoot.getSourceDirectories()) {
                implContentRoot.addSourceDirectory(sourceDirectory.getDirectory());
            }

            impl.addContentRoot(implContentRoot);
        }

        for (IdeaDependency dependency : module.getDependencies()) {
            if (!"COMPILE".equals(dependency.getScope().getScope())) {
                continue;
            }
            if (dependency instanceof ExternalDependency) {
                impl.addLibrary(((ExternalDependency) dependency).getFile());
            } else if (dependency instanceof IdeaModuleDependency) {
                impl.addModuleDependency(((IdeaModuleDependency) dependency).getTargetModuleName());
            }
        }
        // TODO: add child modules
        DomainObjectSet<? extends HierarchicalElement> children = module.getChildren();

        return impl;
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
