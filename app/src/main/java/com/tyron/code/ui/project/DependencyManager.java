package com.tyron.code.ui.project;

import android.util.Log;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.code.util.DependencyUtils;
import com.tyron.common.util.Decompress;
import com.tyron.resolver.DependencyResolver;
import com.tyron.resolver.RepositoryModel;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.LocalRepository;
import com.tyron.resolver.repository.RemoteRepository;
import com.tyron.resolver.repository.Repository;
import com.tyron.resolver.repository.RepositoryManager;
import com.tyron.resolver.repository.RepositoryManagerImpl;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public class DependencyManager {

    private static final String REPOSITORIES_JSON = "repositories.json";

    private final RepositoryManager mRepository;
    private final DependencyResolver mResolver;

    public DependencyManager(JavaModule module, File cacheDir) throws IOException {
        extractCommonPomsIfNeeded();

        mRepository = new RepositoryManagerImpl();
        mRepository.setCacheDirectory(cacheDir);
        for (Repository repository : getFromModule(module)) {
            mRepository.addRepository(repository);
        }
        mRepository.initialize();
        mResolver = new DependencyResolver(mRepository);
    }

    public static List<Repository> getFromModule(JavaModule module) throws IOException {
        File rootFile = module.getRootFile();
        File repositoriesFile = new File(rootFile, REPOSITORIES_JSON);
        List<RepositoryModel> repositoryModels = parseFile(repositoriesFile);
        List<Repository> repositories = new ArrayList<>();
        for (RepositoryModel model : repositoryModels) {
            if (model.getName() == null) {
                continue;
            }
            if (model.getUrl() == null) {
                repositories.add(new LocalRepository(model.getName()));
            } else {
                repositories.add(new RemoteRepository(model.getName(), model.getUrl()));
            }
        }
        return repositories;
    }

    public static List<RepositoryModel> parseFile(File file) throws IOException {
        try {
            String contents = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            Type type = new TypeToken<List<RepositoryModel>>(){}.getType();
            List<RepositoryModel> models = new GsonBuilder()
                    .setLenient()
                    .create()
                    .fromJson(contents, type);
            if (models != null) {
                return models;
            }
        } catch (JsonSyntaxException e) {
            // returning an empty list for now, should probably log this
            return Collections.emptyList();
        } catch (IOException ignored) {
            // add default ones
        }

        List<RepositoryModel> defaultRepositories = getDefaultRepositories();
        String jsonContents = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(defaultRepositories);
        FileUtils.writeStringToFile(file, jsonContents, StandardCharsets.UTF_8);
        return defaultRepositories;
    }

    public static List<RepositoryModel> getDefaultRepositories() {
        return ImmutableList.<RepositoryModel>builder()
                .add(new RepositoryModel("maven", "https://repo1.maven.org/maven2"))
                .add(new RepositoryModel("google-maven", "https://maven.google.com"))
                .add(new RepositoryModel("jitpack", "https://jitpack.io"))
                .add(new RepositoryModel("jcenter", "https://jcenter.bintray.com"))
                .build();
    }

    private void extractCommonPomsIfNeeded() {
        File cacheDir = ApplicationLoader.applicationContext.getExternalFilesDir("cache");
        File pomsDir = new File(cacheDir, "google-maven");
        File[] children = pomsDir.listFiles();
        if (!pomsDir.exists() || children == null || children.length == 0) {
            Decompress.unzipFromAssets(ApplicationLoader.applicationContext, "google-maven.zip", pomsDir.getParent());
        }
    }

    public void resolve(JavaModule project, ProjectManager.TaskListener listener, ILogger logger) throws IOException {
        listener.onTaskStarted("Resolving dependencies");

        mResolver.setResolveListener(new DependencyResolver.ResolveListener() {
            @Override
            public void onResolve(String message) {
                listener.onTaskStarted(message);
            }

            @Override
            public void onFailure(String message) {
                logger.error(message);
            }
        });

        List<Dependency> declaredDependencies = DependencyUtils.parseLibraries(project.getLibraryFile(), logger);
        List<Pom> resolvedPoms = mResolver.resolveDependencies(declaredDependencies);

        listener.onTaskStarted("Downloading dependencies");
        List<Library> files = getFiles(resolvedPoms, logger);

        listener.onTaskStarted("Checking dependencies");
        checkLibraries(project, logger, files);
    }

    private void checkLibraries(JavaModule project, ILogger logger, List<Library> newLibraries) throws IOException {
        Set<Library> libraries = new HashSet<>(newLibraries);

        Map<String, Library> fileLibsHashes = new HashMap<>();
        File[] fileLibraries = project.getLibraryDirectory().listFiles(c ->
                c.getName().endsWith(".aar") || c.getName().endsWith(".jar"));
        if (fileLibraries != null) {
            for (File fileLibrary : fileLibraries) {
                try {
                    ZipFile zipFile = new ZipFile(fileLibrary);
                    Library library = new Library();
                    library.setSourceFile(fileLibrary);
                    fileLibsHashes.put(AndroidUtilities.calculateMD5(fileLibrary), library);
                } catch (IOException e) {
                    String message = "File " + fileLibrary +
                            " is corrupt! Ignoring.";
                    logger.warning(message);
                }
            }
        }


        String librariesString = project.getSettings().getString("libraries", "[]");
        try {
            List<Library> parsedLibraries = new Gson().fromJson(librariesString, new TypeToken<List<Library>>() {
            }.getType());
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
        libraries.forEach(it ->
                md5Map.put(AndroidUtilities.calculateMD5(it.getSourceFile()), it));
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

    private void saveLibraryToProject(Module module, Map<String, Library> libraries, Map<String, Library> fileLibraries) throws IOException {
        Map<String, Library> combined = new HashMap<>();
        combined.putAll(libraries);
        combined.putAll(fileLibraries);

        if (module instanceof JavaModule) {
            ((JavaModule) module).putLibraryHashes(combined);
        }

        for (Map.Entry<String, Library> entry : combined.entrySet()) {
            String hash = entry.getKey();
            Library library = entry.getValue();

            File libraryDir = new File(module.getBuildDirectory(), "libs/" + hash);
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
        module.getSettings().edit()
                .putString("libraries", librariesString)
                .apply();
    }

    public List<Library> getFiles(List<Pom> resolvedPoms,
                                  ILogger logger) {
        List<Library> files = new ArrayList<>();
        for (Pom resolvedPom : resolvedPoms) {
            try {
                File file = mRepository.getLibrary(resolvedPom);
                if (file != null) {
                    Library library = new Library();
                    library.setSourceFile(file);
                    library.setDeclaration(resolvedPom.getDeclarationString());
                    files.add(library);
                }
            } catch (IOException e) {
                logger.error("Unable to download " + resolvedPom + ": " + e.getMessage());
            }
        }
        return files;
    }
}
