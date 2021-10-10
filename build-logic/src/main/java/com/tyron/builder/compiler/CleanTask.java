package com.tyron.builder.compiler;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.Cache;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
        if (mBuildType == BuildType.RELEASE) {
            cleanRelease();
        } else if (mBuildType == BuildType.DEBUG) {
            cleanClasses();
            cleanDexFiles();
        }
    }

    private void cleanRelease() throws IOException {
        File binDirectory = new File(mProject.getBuildDirectory(), "bin");
        FileUtils.delete(binDirectory);

        File genDirectory = new File(mProject.getBuildDirectory(), "gen");
        FileUtils.delete(genDirectory);

        File intermediateDirectory = new File(mProject.getBuildDirectory(), "intermediate");
        FileUtils.delete(intermediateDirectory);

        FileManager.getInstance().getClassCache()
                .clear();
        FileManager.getInstance().getDexCache()
                .clear();
        FileManager.getInstance().getSymbolCache()
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
                path = path.substring(0, path.lastIndexOf("$"));
            }

            if (FileManager.getInstance().list(path).isEmpty() && !FileManager.getInstance().containsClass(packageName)) {
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
            if (path.contains("$")) {
                path = path.substring(0, path.lastIndexOf("$"));
            }

            if (FileManager.getInstance().list(path).isEmpty() && !FileManager.getInstance().containsClass(packageName)) {
                if (file.delete()) {
                    mLogger.debug("Deleted dex file " + file.getName());
                }
            }
        }
    }
}
