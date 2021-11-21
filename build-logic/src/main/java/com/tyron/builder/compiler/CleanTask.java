package com.tyron.builder.compiler;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class CleanTask extends Task {

    private static final String TAG = CleanTask.class.getSimpleName();

    private Project mProject;
    private ILogger mLogger;
    private BuildType mBuildType;

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mProject = project;
        mLogger = logger;
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
        mLogger.info("Release build, clearing intermediate cache");

        File binDirectory = new File(mProject.getBuildDirectory(), "bin");
        if (binDirectory.exists()) {
            FileUtils.deleteDirectory(binDirectory);
        }

        File genDirectory = new File(mProject.getBuildDirectory(), "gen");
        if (genDirectory.exists()) {
            FileUtils.deleteDirectory(genDirectory);
        }

        File intermediateDirectory = new File(mProject.getBuildDirectory(), "intermediate");
        if (intermediateDirectory.exists()) {
            FileUtils.deleteDirectory(intermediateDirectory);
        }

        mProject.getFileManager().getClassCache()
                .clear();
        mProject.getFileManager().getDexCache()
                .clear();
        mProject.getFileManager().getSymbolCache()
                .clear();
    }
    private void cleanClasses() {

        File output = new File(mProject.getBuildDirectory(), "bin/classes/");
        List<File> classFiles = FileManager.findFilesWithExtension(
                output,
                ".class");
        for (File file : classFiles) {
            String path = file.getAbsolutePath()
                    .replace(output.getAbsolutePath(), "")
                    .replace("/", ".")
                    .substring(1)
                    .replace(".class", "");

            String packageName = path;
            if (path.contains("$")) {
                path = path.substring(0, path.indexOf("$"));
            }

            if (mProject.getFileManager().list(path).isEmpty() && !classExists(packageName)) {
                if (file.delete()) {
                    mLogger.debug("Deleted class file " + file.getName());
                };
            }
        }
    }

    private void cleanDexFiles() {
        File output = new File(mProject.getBuildDirectory(), "intermediate/classes");
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
                    mLogger.warning("Unrecognized dex file: " + file.getName());
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

            if (mProject.getFileManager().list(path).isEmpty() && !classExists(packageName)) {
                if (file.delete()) {
                    mLogger.debug("Deleted dex file " + path);
                }
            }
        }
    }

    private boolean classExists(String fqn) {
        return mProject.getJavaFiles().get(fqn) != null ||
                mProject.getKotlinFiles().get(fqn) != null;
    }
}
