package com.tyron.code.compiler.manifest;

import com.tyron.code.compiler.Task;
import com.tyron.code.model.Project;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ManifestMergeTask extends Task {

    private ManifestMerger mMerger;
    private File mOutputFile;
    private File mMainManifest;
    private File[] mLibraryManifestFiles;

    @Override
    public String getName() {
        return "ManifestMerger";
    }

    @Override
    public void prepare(Project project, ILogger logger) throws IOException {
        mMerger = new ManifestMerger(MergerLog.wrapSdkLog(logger), null);

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
                manifests.add(manifest);
            }
        }

        mLibraryManifestFiles = manifests.toArray(new File[0]);
    }


    @Override
    public void run() throws IOException, CompilationFailedException {
        mMerger.process(mOutputFile, mMainManifest, mLibraryManifestFiles, null, null);
    }
}
