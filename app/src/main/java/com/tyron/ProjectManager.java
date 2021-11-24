package com.tyron;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Library;
import com.tyron.builder.model.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.code.util.ProjectUtils;
import com.tyron.common.util.Decompress;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static volatile ProjectManager INSTANCE = null;

    public static synchronized ProjectManager getInstance() {
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
            try {
                proj.open();
                mCurrentProject = proj;

                proj.getLibraries().clear();
                if (downloadLibs) {
                    downloadLibraries(proj, mListener, logger);
                } else {
                    checkLibraries(proj, Collections.emptySet());
                }
            } catch (IOException e) {
                mListener.onComplete(false, "Unable to open project: " + e.getMessage());
                return;
            }
            mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentProject));
            //mCompletionEnvironment = CompletionEnvironment.newInstance(proj.getJavaFiles().values(), proj.getKotlinFiles().values(), proj.getLibraries());

            mListener.onTaskStarted("Indexing");
            CompletionEngine.getInstance().index(proj, () -> mListener.onComplete(true, "Index successful"));
        });
    }

    private void downloadLibraries(Project project, TaskListener mListener, ILogger logger) throws IOException {
        mListener.onTaskStarted("Resolving dependencies");
        // this is the existing libraries from app/libs
        Set<Dependency> libs = new HashSet<>();

        // dependencies parsed from the build.gradle file
        Set<Dependency> dependencies = new HashSet<>();
        try {
            dependencies.addAll(DependencyUtils.parseGradle(new File(project.mRoot, "app/build.gradle")));
        } catch (Exception exception) {
            //TODO: handle parse error
            mListener.onComplete(false, exception.getMessage());
        }

        DependencyResolver resolver = new DependencyResolver(dependencies);
        resolver.addResolvedLibraries(libs);
        resolver.setListener(d -> mListener.onTaskStarted("Resolving " + d.toString()));
        dependencies = resolver.resolveMain();

        mListener.onTaskStarted("Downloading dependencies");
        DependencyDownloader downloader = new DependencyDownloader(libs, project.getLibraryDirectory());
        downloader.setListener(d -> mListener.onTaskStarted("Downloading " + d.toString()));
        try {
            downloader.cache(dependencies);
        } catch (IOException e) {
            logger.warning("Unable to download dependencies: " + e.getMessage());
        }

        checkLibraries(project, dependencies);
    }

    private void checkLibraries(Project project, Collection<Dependency> justDownloaded) throws IOException {
        Set<Library> libraries = new HashSet<>();

        Map<String, Library> fileLibsHashes = new HashMap<>();
        File[] fileLibraries = project.getLibraryDirectory().listFiles(c -> c.getName().endsWith(".aar") || c.getName().endsWith(".jar"));
        if (fileLibraries != null) {
            for (File fileLibrary : fileLibraries) {
                Library library = new Library();
                library.setSourceFile(fileLibrary);
                fileLibsHashes.put(AndroidUtilities.calculateMD5(fileLibrary), library);
            }
        }

        justDownloaded.forEach(it -> {
            File libraryFile = getFromCache(it);
            if (libraryFile != null) {
                Library library = new Library();
                library.setSourceFile(libraryFile);
                libraries.add(library);
            }
        });

        String librariesString = project.getSettings().getString("libraries", "[]");
        try {
            List<Library> parsedLibraries = new Gson().fromJson(librariesString, new TypeToken<List<Library>>(){}.getType());
            if (parsedLibraries != null) {
                for (Library parsedLibrary : parsedLibraries) {
                    if (!libraries.contains(parsedLibrary)) {
                        Log.d("LibraryCheck", "Removed library" + parsedLibrary);
                    } else {
                        libraries.add(parsedLibrary);
                    }
                }
            }
        } catch (Exception ignore) {

        }


        Map<String, Library> md5Map = new HashMap<>();
        libraries.forEach(it -> {
            md5Map.put(AndroidUtilities.calculateMD5(it.getSourceFile()), it);
        });

        File buildLibs = new File(project.getBuildDirectory(), "libs");
        File[] buildLibraryDirs = buildLibs.listFiles(File::isDirectory);
        if (buildLibraryDirs != null) {
            for (File libraryDir : buildLibraryDirs) {
                String md5Hash = libraryDir.getName();
                if (!md5Map.containsKey(md5Hash) && !fileLibsHashes.containsKey(md5Hash)) {
                    FileUtils.deleteDirectory(libraryDir);
                    Log.d("LibraryCheck", "Deleting contents of " + md5Hash);
                }
            }
        }

        saveLibraryToProject(project, md5Map, fileLibsHashes);
    }

    @SuppressWarnings({"ResultOfMethodCallIgnored"})
    private void saveLibraryToProject(Project project, Map<String, Library> libraries, Map<String, Library> fileLibraries) throws IOException {
        Map<String, Library> combined = new HashMap<>();
        combined.putAll(libraries);
        combined.putAll(fileLibraries);
        for (Map.Entry<String, Library> entry : combined.entrySet()) {
            String hash = entry.getKey();
            Library library = entry.getValue();

            File libraryDir = new File(project.getBuildDirectory(), "libs/" + hash);
            if (!libraryDir.exists()) {
                libraryDir.mkdir();
            } else {
                continue;
            }

            if (library.getSourceFile().getName().endsWith(".jar")) {
                FileUtils.copyFileToDirectory(library.getSourceFile(), libraryDir);

                File file = new File(libraryDir, library.getSourceFile().getName());
                file.renameTo(new File(libraryDir, "classes.jar"));
            } else if (library.getSourceFile().getName().endsWith(".aar")) {
                Decompress.unzip(library.getSourceFile().getAbsolutePath(),
                        libraryDir.getAbsolutePath());
            }
        }

        String librariesString = new Gson().toJson(libraries.values());
        project.getSettings().edit()
                .putString("libraries", librariesString)
                .apply();
    }

    private File getFromCache(Dependency dependency) {
        File librariesFolder = new File(ApplicationLoader.applicationContext.getCacheDir(),
                "libraries");
        if (!librariesFolder.exists()) {
            return null;
        }

        File[] files = librariesFolder.listFiles(c -> {
            String name = c.getName();
            if (name.endsWith(".jar") || name.endsWith(".aar")) {
                name = name.substring(0, name.length() - ".jar".length());
            }
            return name.equals(dependency.toString());
        });

        if (files == null || files.length != 1) {
            return null;
        }

        return files[0];
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
