package com.tyron.builder.compiler;

import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.symbol.MergeSymbolsTask;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.parser.FileManager;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.Cache;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class CleanTask extends Task<AndroidModule> {

    private static final String TAG = CleanTask.class.getSimpleName();

    private BuildType mBuildType;

    public CleanTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mBuildType = type;
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mBuildType == BuildType.RELEASE || mBuildType == BuildType.AAB) {
            cleanRelease();
        } else if (mBuildType == BuildType.DEBUG) {
            cleanClasses();
            cleanDexFiles();
        }
    }

    private void cleanRelease() throws IOException {
        getLogger().info("Release build, clearing intermediate cache");

        File binDirectory = new File(getModule().getBuildDirectory(), "bin");
        if (binDirectory.exists()) {
            FileUtils.deleteDirectory(binDirectory);
        }

        File genDirectory = new File(getModule().getBuildDirectory(), "gen");
        if (genDirectory.exists()) {
            FileUtils.deleteDirectory(genDirectory);
        }

        File intermediateDirectory = new File(getModule().getBuildDirectory(), "intermediate");
        if (intermediateDirectory.exists()) {
            FileUtils.deleteDirectory(intermediateDirectory);
        }

        getModule().getCache(IncrementalJavaTask.CACHE_KEY, new Cache<>())
                .clear();
        getModule().getCache(IncrementalD8Task.CACHE_KEY, new Cache<>())
                .clear();
        getModule().getCache(MergeSymbolsTask.CACHE_KEY, new Cache<>())
                .clear();
    }
    private void cleanClasses() {

        File output = new File(getModule().getBuildDirectory(), "bin/classes/");
        List<File> classFiles = FileManager.findFilesWithExtension(
                output,
                ".class");
        for (File file : classFiles) {
            String path = file.getAbsolutePath()
                    .replace(output.getAbsolutePath(), "")
                    .replace("/", ".")
                    .substring(1)
                    .replace(".class", "");

            if (!classExists(path)) {
                if (file.delete()) {
                    getLogger().debug("Deleted class file " + file.getName());
                };
            }
        }
    }

    private void cleanDexFiles() {
        File output = new File(getModule().getBuildDirectory(), "intermediate/classes");
        List<File> classFiles = FileManager.findFilesWithExtension(
                output,
                ".dex");

        for (File file : classFiles) {
            String path = file.getAbsolutePath()
                    .replace(output.getAbsolutePath(), "")
                    .replace("/", ".")
                    .substring(1)
                    .replace(".dex", "");
            String packageName = path;

            if (file.getName().startsWith("-$$")) {
                String name = file.getName().replace(".dex", "");
                int start = name.indexOf('$', 3) + 1;
                int end = name.indexOf('$', start);
                if (start == -1 || end == -1) {
                    getLogger().warning("Unrecognized dex file: " + file.getName());
                    continue;
                } else {
                    String className = name.substring(start, end);
                    path = path.substring(0, path.lastIndexOf('.')) + "." + className;
                }
            } else {
                if (path.contains("$")) {
                    path = path.substring(0, path.indexOf("$"));
                }
            }

            if (!classExists(packageName)) {
                if (file.delete()) {
                    getLogger().debug("Deleted dex file " + path);
                }
            }
        }
    }

    private boolean classExists(String fqn) {
        if (fqn.contains("$")) {
            fqn = fqn.substring(0, fqn.indexOf('$'));
        }
        return getModule().getJavaFiles().get(fqn) != null ||
               getModule().getKotlinFiles().get(fqn) != null ||
               getModule().getResourceClasses().containsKey(fqn);
    }
}
