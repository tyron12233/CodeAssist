package com.tyron.builder.compiler.aab;

import android.util.Log;

import com.tyron.builder.BuildModule;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.incremental.resource.ResourceFile;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.BinaryExecutor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class AabTask extends Task {

    private static final String TAG = "AabTask";

    private Project mProject;
    private ILogger mLogger;
    private File dex;
    private File mBinDir;
    private File base;
    private File manifest;
    private File bin;
    private File jars;

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mProject = project;
        mLogger = logger;
        mBinDir = new File(project.getBuildDirectory(), "/bin");
        base = new File(mBinDir.getAbsolutePath(), "/base");


        if (!base.exists()) {
            if (!base.mkdirs()) {
                throw new IOException("Failed to create resource output directory");
            }
        }


        manifest = new File(mBinDir.getAbsolutePath(), "/base/manifest");
        if (!manifest.exists()) {
            if (!manifest.mkdirs()) {
                throw new IOException("Failed to create resource output directory");
            }
        }

        dex = new File(mBinDir.getAbsolutePath(), "/base/dex");
        if (!dex.exists()) {
            if (!dex.mkdirs()) {
                throw new IOException("Failed to create resource output directory");
            }
        }

    }

    public void run() throws IOException, CompilationFailedException {
        Map<String, List<File>> filesToCompile = getFiles();
        List<File> librariesToCompile = getLibraries();
        link();
        unZip();
        copy();
        copyJni();
        baseZip();
        budletool();
        aab();
        buildApks();
        extractApks();
    }

    private void extractApks() throws IOException {
        mLogger.debug("Extracting Apks");
        String Apks = mBinDir.getAbsolutePath() + "/App.apks";
        String dApks = mBinDir.getAbsolutePath() + "";
        uApks(Apks, dApks);
    }

    private static void uApks(String Apks, String dApks) {
        File dir = new File(dApks);
        // create output directory if it doesn't exist
        if (!dir.exists()) {
            dir.mkdirs();
        }
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(Apks);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(dApks + File.separator + fileName);
                System.out.println("Unzipping to " + newFile.getAbsolutePath());
                //create directories for sub directories in zip
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch
        (IOException e) {
            e.printStackTrace();
        }
    }


    private void buildApks() throws IOException, CompilationFailedException {
        mLogger.debug("Building Apks");

        List<String> args = new ArrayList<>();
        args.add("dalvikvm");
        args.add("-Xcompiler-option");
        args.add("--compiler-filter=speed");
        args.add("-Xmx256m");
        args.add("-Djava.io.tmpdir=" + BuildModule.getContext().getCacheDir().getAbsolutePath());
        args.add("-cp");
        //bundletool.jar should be in download folder
        args.add(BuildModule.getContext().getFilesDir() + "/bundletool.jar");
        //args.add("/storage/emulated/0/Download/bundletool.jar");
        args.add("com.android.tools.build.bundletool.BundleToolMain");
        args.add("build-apks");
        args.add("--bundle=" + mBinDir.getAbsolutePath() + "/module.aab");
        args.add("--output=" + mBinDir.getAbsolutePath() + "/App.apks");
        args.add("--mode=universal");
        args.add("--aapt2=" + BuildModule.getContext().getApplicationInfo().nativeLibraryDir + "/libaapt2.so");


        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(args);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }

    private void budletool() throws IOException {
        mLogger.debug("Preparing Bundletool");

        if (!new File(BuildModule.getContext().getFilesDir(), "bundletool.jar").exists()) {
            try {
                InputStream input = BuildModule.getContext().getAssets().open("bundletool.jar");
                OutputStream output = new FileOutputStream(new File(BuildModule.getContext().getFilesDir(), "bundletool.jar"));
                IOUtils.copy(input, output);
                input.close();
                output.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private void aab() throws IOException, CompilationFailedException {
        mLogger.debug("Generating AAB.");

        List<String> args = new ArrayList<>();
        args.add("dalvikvm");
        args.add("-Xcompiler-option");
        args.add("--compiler-filter=speed");
        args.add("-Xmx256m");
        args.add("-Djava.io.tmpdir=" + BuildModule.getContext().getCacheDir().getAbsolutePath());
        args.add("-cp");
        //bundletool.jar should be in download folder
        args.add(BuildModule.getContext().getFilesDir() + "/bundletool.jar");
        //args.add("/storage/emulated/0/Download/bundletool.jar");
        args.add("com.android.tools.build.bundletool.BundleToolMain");
        args.add("build-bundle");
        args.add("--modules=" + mBinDir.getAbsolutePath() + "/Base-Module.zip");
        args.add("--output=" + mBinDir.getAbsolutePath() + "/module.aab");


        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(args);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }


    private void baseZip() throws IOException {
        mLogger.debug("Creating Module Archieve");
        String folderToZip = base.getAbsolutePath();
        String zipName = mBinDir.getAbsolutePath() + "/Base-Module.zip";
        zipFolder(Paths.get(folderToZip), Paths.get(zipName));
    }

    // Uses java.util.zip to create zip file
    private void zipFolder(final Path sourceFolderPath, Path zipPath) throws IOException {
        final ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
        Files.walkFileTree(sourceFolderPath, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                zos.putNextEntry(new ZipEntry(sourceFolderPath.relativize(file).toString()));
                Files.copy(file, zos);
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
        zos.close();
    }


    private void copy() throws IOException, CompilationFailedException {
        mLogger.debug("Coping Manifest.");

        List<String> args = new ArrayList<>();
        args.add("mv");
        args.add(base.getAbsolutePath() + "/AndroidManifest.xml");
        args.add(manifest.getAbsolutePath() + "");
        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(args);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }

    private void copyJni() throws IOException {
        mLogger.debug("Coping JniLibs.");
        String fromDirectory = mProject.getNativeLibsDirectory().getAbsolutePath();
        String toToDirectory = base.getAbsolutePath() + "/lib";

        try {

            copyDirectoryFileVisitor(fromDirectory, toToDirectory);

        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Done");

    }

    public static void copyDirectoryFileVisitor(String source, String target)
            throws IOException {

        TreeCopyFileVisitor fileVisitor = new TreeCopyFileVisitor(source, target);
        Files.walkFileTree(Paths.get(source), fileVisitor);

    }


    private void unZip() throws IOException {
        mLogger.debug("Unziping ProtoFormat.");

        String zipFilePath = mBinDir.getAbsolutePath() + "/proto-format.zip";
        String destDir = base.getAbsolutePath() + "";
        unzip(zipFilePath, destDir);
    }

    private static void unzip(String zipFilePath, String destDir) {
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) {
            dir.mkdirs();
        }
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                System.out.println("Unzipping to " + newFile.getAbsolutePath());
                //create directories for sub directories in zip
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch
        (IOException e) {
            e.printStackTrace();
        }
    }


    private void link() throws IOException, CompilationFailedException {
        mLogger.debug("Generating ProtoFormat");

        List<String> args = new ArrayList<>();

        args.add(getBinary().getAbsolutePath());
        args.add("link");
        args.add("--proto-format");
        args.add("-o");
        args.add(getOutputPath().getParent() + "/proto-format.zip");
        args.add("-I");
        args.add(FileManager.getAndroidJar().getAbsolutePath());
        args.add("--manifest");
        File mergedManifest = new File(mProject.getBuildDirectory(), "bin/AndroidManifest.xml");
        if (!mergedManifest.exists()) {
            throw new IOException("Unable to get merged manifest file");
        }
        args.add(mergedManifest.getAbsolutePath());


        File files = new File(getOutputPath(), "compiled");
        File[] resources = files.listFiles();
        if (resources == null) {
            throw new CompilationFailedException("No files to compile");
        }
        for (File resource : resources) {
            if (!resource.getName().endsWith(".flat")) {
                mLogger.warning("Unrecognized file " + resource.getName() + " at compiled directory");
                continue;
            }
            args.add(resource.getAbsolutePath());
            if (mProject.getAssetsDirectory().exists()) {
                args.add("-A");
                args.add(mProject.getAssetsDirectory().getAbsolutePath());
            }


        }

        args.add("--auto-add-overlay");
        args.add("--min-sdk-version");
        args.add(String.valueOf(mProject.getMinSdk()));
        args.add("--target-sdk-version");
        args.add(String.valueOf(mProject.getTargetSdk()));

        resources = getOutputPath().listFiles();
        if (resources != null) {
            for (File resource : resources) {
                if (resource.isDirectory()) {
                    continue;
                }
                if (!resource.getName().endsWith(".zip")) {
                    mLogger.warning("Unrecognized file " + resource.getName());
                    continue;
                }

                if (resource.length() == 0) {
                    mLogger.warning("Empty zip file " + resource.getName());
                }

                args.add("-R");
                args.add(resource.getAbsolutePath());
            }
        }

        args.add("--java");
        File gen = new File(mProject.getBuildDirectory(), "gen");
        if (!gen.exists()) {
            if (!gen.mkdirs()) {
                throw new CompilationFailedException("Failed to create gen folder");
            }
        }
        args.add(gen.getAbsolutePath());


        BinaryExecutor exec = new BinaryExecutor();
        exec.setCommands(args);
        if (!exec.execute().trim().isEmpty()) {
            throw new CompilationFailedException(exec.getLog());
        }
    }

    /**
     * Utility function to get all the files that needs to be recompiled
     *
     * @return resource files to compile
     */
    public Map<String, List<File>> getFiles() throws IOException {
        Map<String, List<ResourceFile>> newFiles = findFiles(mProject.getResourceDirectory());
        Map<String, List<ResourceFile>> oldFiles = findFiles(getOutputDirectory());
        Map<String, List<File>> filesToCompile = new HashMap<>();

        for (String resourceType : newFiles.keySet()) {

            // if the cache doesn't contain the new files then its considered new
            if (!oldFiles.containsKey(resourceType)) {
                List<ResourceFile> files = newFiles.get(resourceType);
                if (files != null) {
                    addToMapList(filesToCompile, resourceType, files);
                }
                continue;
            }

            // both contain the resource type, compare the contents
            if (oldFiles.containsKey(resourceType)) {
                List<ResourceFile> newFilesResource = newFiles.get(resourceType);
                List<ResourceFile> oldFilesResource = oldFiles.get(resourceType);

                if (newFilesResource == null) {
                    newFilesResource = Collections.emptyList();
                }
                if (oldFilesResource == null) {
                    oldFilesResource = Collections.emptyList();
                }

                addToMapList(filesToCompile, resourceType, getModifiedFiles(newFilesResource, oldFilesResource));
            }
        }

        for (String resourceType : oldFiles.keySet()) {
            // if the new files doesn't contain the old file then its deleted
            if (!newFiles.containsKey(resourceType)) {
                Log.d("IncrementalAAPT2", "Deleting resource folder " + resourceType);
                List<ResourceFile> files = oldFiles.get(resourceType);
                if (files != null) {
                    for (File file : files) {
                        if (!file.delete()) {
                            throw new IOException("Failed to delete file " + file);
                        }
                    }
                }
            }
        }

        return filesToCompile;
    }

    /**
     * Utility method to add a list of files to a map, if it doesn't exist, it creates a new one
     *
     * @param map    The map to add to
     * @param key    Key to add the value
     * @param values The list of files to add
     */
    private void addToMapList(Map<String, List<File>> map, String key, List<ResourceFile> values) {
        List<File> mapValues = map.get(key);
        if (mapValues == null) {
            mapValues = new ArrayList<>();
        }

        mapValues.addAll(values);
        map.put(key, mapValues);
    }

    private void copyMapToDir(Map<String, List<File>> map) throws IOException {
        File output = new File(mProject.getBuildDirectory(), "intermediate/resources");
        if (!output.exists()) {
            if (!output.mkdirs()) {
                throw new IOException("Failed to create intermediate directory");
            }
        }

        for (String resourceType : map.keySet()) {
            File outputDir = new File(output, resourceType);
            if (!outputDir.exists()) {
                if (!outputDir.mkdir()) {
                    throw new IOException("Failed to create output directory for " + outputDir);
                }
            }

            List<File> files = map.get(resourceType);
            if (files != null) {
                for (File file : files) {
                    FileUtils.copyFileToDirectory(file, outputDir, true);
                }
            }
        }
    }

    /**
     * Utility method to compare to list of files
     */
    private List<ResourceFile> getModifiedFiles(List<ResourceFile> newFiles, List<ResourceFile> oldFiles) throws IOException {
        List<ResourceFile> resourceFiles = new ArrayList<>();

        for (ResourceFile newFile : newFiles) {
            if (!oldFiles.contains(newFile)) {
                resourceFiles.add(newFile);
            } else {
                File oldFile = oldFiles.get(oldFiles.indexOf(newFile));
                if (contentModified(newFile, oldFile)) {
                    resourceFiles.add(newFile);
                    if (!oldFile.delete()) {
                        throw new IOException("Failed to delete file " + oldFile.getName());
                    }
                }
                oldFiles.remove(oldFile);
            }
        }

        for (ResourceFile removedFile : oldFiles) {
            if (!removedFile.delete()) {
                throw new IOException("Failed to delete old file " + removedFile);
            }
        }

        return resourceFiles;
    }

    private boolean contentModified(File newFile, File oldFile) {
        if (!oldFile.exists() || !newFile.exists()) {
            return true;
        }

        if (newFile.length() != oldFile.length()) {
            return true;
        }

        return newFile.lastModified() > oldFile.lastModified();
    }

    /**
     * Returns a map of resource type, and the files for a given resource directory
     *
     * @param file res directory
     * @return Map of resource type and the files corresponding to it
     */
    private Map<String, List<ResourceFile>> findFiles(File file) {
        File[] children = file.listFiles();
        if (children == null) {
            return Collections.emptyMap();
        }

        Map<String, List<ResourceFile>> map = new HashMap<>();
        for (File child : children) {
            if (!file.isDirectory()) {
                continue;
            }

            String resourceType = child.getName();
            File[] resourceFiles = child.listFiles();
            List<File> files;
            if (resourceFiles == null) {
                files = Collections.emptyList();
            } else {
                files = Arrays.asList(resourceFiles);
            }


            map.put(resourceType, files.stream().map(ResourceFile::fromFile).collect(Collectors.toList()));
        }

        return map;
    }

    /**
     * Returns the list of resource directories of libraries that needs to be compiled
     * It determines whether the library should be compiled by checking the build/bin/res folder,
     * if it contains a zip file with its name, then its most likely the same library
     */
    private List<File> getLibraries() throws IOException {
        File resDir = new File(mProject.getBuildDirectory(), "bin/res");
        if (!resDir.exists()) {
            if (!resDir.mkdirs()) {
                throw new IOException("Failed to create resource directory");
            }
        }

        List<File> libraries = new ArrayList<>();

        for (File library : mProject.getLibraries()) {
            File parent = library.getParentFile();
            if (parent != null) {

                if (!new File(parent, "res").exists()) {
                    // we don't need to check it if it has no resource directory
                    continue;
                }

                File check = new File(resDir, parent.getName() + ".zip");
                if (!check.exists()) {
                    libraries.add(library);
                }
            }
        }

        return libraries;
    }

    private File createNewFile(File parent, String name) throws IOException {
        File createdFile = new File(parent, name);
        if (!parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Unable to create directories");
            }
        }
        if (!createdFile.createNewFile()) {
            throw new IOException("Unable to create file " + name);
        }
        return createdFile;
    }

    private File getOutputDirectory() throws IOException {
        File intermediateDirectory = new File(mProject.getBuildDirectory(), "intermediate");

        if (!intermediateDirectory.exists()) {
            if (!intermediateDirectory.mkdirs()) {
                throw new IOException("Failed to create intermediate directory");
            }
        }

        File resourceDirectory = new File(intermediateDirectory, "resources");
        if (!resourceDirectory.exists()) {
            if (!resourceDirectory.mkdirs()) {
                throw new IOException("Failed to create resource directory");
            }
        }
        return resourceDirectory;
    }

    private File getOutputPath() throws IOException {
        File file = new File(mProject.getBuildDirectory(), "bin/res");
        if (!file.exists()) {
            if (!file.mkdirs()) {
                throw new IOException("Failed to get resource directory");
            }
        }
        return file;
    }

    private static File getBinary() throws IOException {
        File check = new File(
                BuildModule.getContext().getApplicationInfo().nativeLibraryDir,
                "libaapt2.so"
        );
        if (check.exists()) {
            return check;
        }

        throw new IOException("AAPT2 Binary not found");
    }
}
