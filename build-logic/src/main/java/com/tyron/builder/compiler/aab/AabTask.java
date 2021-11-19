package com.tyron.builder.compiler.aab;

import com.tyron.builder.BuildModule;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.common.util.BinaryExecutor;
import com.tyron.common.util.Decompress;

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
import java.util.List;
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

        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("Failed to create resource output directory");
        }

        manifest = new File(mBinDir.getAbsolutePath(), "/base/manifest");
        if (!manifest.exists() && !manifest.mkdirs()) {
            throw new IOException("Failed to create resource output directory");
        }

        dex = new File(mBinDir.getAbsolutePath(), "/base/dex");
        if (!dex.exists() && !dex.mkdirs()) {
            throw new IOException("Failed to create resource output directory");
        }

    }

    public void run() throws IOException, CompilationFailedException {
        unZip();
        copyManifest();
        copyJni();
        copyDexFiles();
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

    private static void uApks(String Apks, String dApks) throws IOException {
        File dir = new File(dApks);
        // create output directory if it doesn't exist
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + dir);
        }

        try (FileInputStream fis = new FileInputStream(Apks)) {
            byte[] buffer = new byte[1024];
            try (ZipInputStream zis = new ZipInputStream(fis)) {
                ZipEntry ze = zis.getNextEntry();
                while (ze != null) {
                    String fileName = ze.getName();
                    File newFile = new File(dApks + File.separator + fileName);
                    //create directories for sub directories in zip
                    File parent = newFile.getParentFile();
                    if (parent != null) {
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Failed to create directories: " + parent);
                        }
                    }
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    ze = zis.getNextEntry();
                }
            }
        }
    }

    private void buildApks() throws CompilationFailedException {
        mLogger.debug("Building Apks");
        List<String> args = new ArrayList<>();
        args.add("dalvikvm");
        args.add("-Xcompiler-option");
        args.add("--compiler-filter=speed");
        args.add("-Xmx256m");
        args.add("-Djava.io.tmpdir=" + BuildModule.getContext().getCacheDir().getAbsolutePath());
        args.add("-cp");
        args.add(BuildModule.getContext().getFilesDir() + "/bundletool.jar");
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

        File bundletool = new File(BuildModule.getContext().getFilesDir(), "bundletool.jar");
        if (!bundletool.exists()) {
            InputStream input = BuildModule.getContext()
                    .getAssets().open("bundletool.jar");
            OutputStream output = new FileOutputStream(
                    new File(BuildModule.getContext().getFilesDir(), "bundletool.jar"));
            IOUtils.copy(input, output);
        }
    }


    private void aab() throws CompilationFailedException {
        mLogger.debug("Generating AAB.");

        List<String> args = new ArrayList<>();
        args.add("dalvikvm");
        args.add("-Xcompiler-option");
        args.add("--compiler-filter=speed");
        args.add("-Xmx256m");
        args.add("-Djava.io.tmpdir=" + BuildModule.getContext().getCacheDir().getAbsolutePath());
        args.add("-cp");
        args.add(BuildModule.getContext().getFilesDir() + "/bundletool.jar");
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
        mLogger.debug("Creating Module Archive");
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


    private void copyManifest() throws CompilationFailedException {
        mLogger.debug("Copying Manifest.");

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

    private void copyDexFiles() throws IOException {
        File[] dexFiles = mBinDir.listFiles(c ->
                c.isFile() && c.getName().endsWith(".dex")
        );

        File dexOutput = new File(mBinDir, "base/dex");
        if (dexFiles != null) {
            for (File dexFile : dexFiles) {
                FileUtils.copyFileToDirectory(dexFile, dexOutput);
            }
        }
    }

    private void copyJni() throws IOException {
        mLogger.debug("Coping JniLibs.");
        String fromDirectory = mProject.getNativeLibsDirectory().getAbsolutePath();
        String toToDirectory = base.getAbsolutePath() + "/lib";
        copyDirectoryFileVisitor(fromDirectory, toToDirectory);
    }

    public static void copyDirectoryFileVisitor(String source, String target)
            throws IOException {
        TreeCopyFileVisitor fileVisitor = new TreeCopyFileVisitor(source, target);
        Files.walkFileTree(Paths.get(source), fileVisitor);
    }


    private void unZip() throws IOException {
        mLogger.debug("Unzipping proto format.");
        String zipFilePath = mBinDir.getAbsolutePath() + "/proto-format.zip";
        String destDir = base.getAbsolutePath() + "";
        Decompress.unzip(zipFilePath, destDir);
    }
}
