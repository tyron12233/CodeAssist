package com.tyron.builder.compiler.manifest;

import com.tyron.builder.compiler.Task;
import com.tyron.builder.model.Project;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ManifestMergeTask extends Task {

    private ManifestMerger2 mMerger;
    private File mOutputFile;
    private File mMainManifest;
    private File[] mLibraryManifestFiles;
    private String mPackageName;

    private ILogger mLogger;

    @Override
    public String getName() {
        return "ManifestMerger";
    }

    @Override
    public void prepare(Project project, ILogger logger) throws IOException {
        mLogger = logger;

        mPackageName = getApplicationId(project);

        mOutputFile = new File(project.getBuildDirectory(), "bin");
        if (!mOutputFile.exists()) {
            if (!mOutputFile.mkdirs()) {
                throw new IOException("Unable to create build directory");
            }
        }
        mOutputFile = new File(mOutputFile, "AndroidManifest.xml");
        if (!mOutputFile.exists()) {
            if (!mOutputFile.createNewFile()) {
                throw new IOException("Unable to create manifest file");
            }
        }

        mMainManifest = project.getManifestFile();
        if (!mMainManifest.exists()) {
            throw new IOException("Unable to find the main manifest file");
        }

        List<File> manifests = new ArrayList<>();
        Set<File> libraries = project.getLibraries();
        // Filter the libraries and add all that has a AndroidManifest.xml file
        for (File library : libraries) {
            File parent = library.getParentFile();
            if (parent == null) {
                logger.warning("Unable to access parent directory of a library");
                continue;
            }

            File manifest = new File(parent, "AndroidManifest.xml");
            if (manifest.exists()) {
                if (manifest.length() != 0) {
                    manifests.add(manifest);
                }
            }
        }

        mLibraryManifestFiles = manifests.toArray(new File[0]);
    }


    @Override
    public void run() throws IOException, CompilationFailedException {

        if (mLibraryManifestFiles == null || mLibraryManifestFiles.length == 0) {
            // no libraries to merge, just copy the manifest file to the output
            FileUtils.copyFile(mMainManifest, mOutputFile);
            return;
        }

        ManifestMerger2.Invoker invoker = ManifestMerger2.newMerger(mMainManifest,
                mLogger, ManifestMerger2.MergeType.APPLICATION)
                .addLibraryManifests(mLibraryManifestFiles);
        invoker.setOverride(ManifestMerger2.SystemProperty.PACKAGE, mPackageName);

        try {
            MergingReport report = invoker.merge();
            if (report.getResult().isError()) {
                report.log(mLogger);
                throw new CompilationFailedException(report.getReportString());
            }
            if (report.getMergedDocument().isPresent()) {
                FileUtils.writeStringToFile(mOutputFile, report.getMergedDocument().get().prettyPrint(), Charset.defaultCharset());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            throw new CompilationFailedException(e);
        }
    }

    private String getApplicationId(Project project) throws IOException {
        String packageName = project.getPackageName();
        if (packageName == null) {
            throw new IOException("Failed to parse package name");
        }
        return packageName;
    }
}
