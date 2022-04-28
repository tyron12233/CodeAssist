package com.tyron.builder.compiler.manifest;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.manifest.ManifestMerger2.SystemProperty;
import com.tyron.builder.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.builder.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.builder.compiler.manifest.xml.XmlPrettyPrinter;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;

import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class ManifestMergeTask extends Task<AndroidModule> {

    private File mOutputFile;
    private File mMainManifest;
    private File[] mLibraryManifestFiles;
    private String mPackageName;

    public ManifestMergeTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return "ManifestMerger";
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mPackageName = getApplicationId();

        mOutputFile = new File(getModule().getBuildDirectory(), "bin");
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

        mMainManifest = getModule().getManifestFile();
        if (!mMainManifest.exists()) {
            throw new IOException("Unable to find the main manifest file");
        }

        List<File> manifests = new ArrayList<>();
        List<File> libraries = getModule().getLibraries();

        // Filter the libraries and add all that has a AndroidManifest.xml file
        for (File library : libraries) {
            File parent = library.getParentFile();
            if (parent == null) {
                getLogger().warning("Unable to access parent directory of a library");
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
        ModuleSettings settings = getModule().getSettings();

        ManifestMerger2.Invoker<?> invoker = ManifestMerger2.newMerger(mMainManifest,
                getLogger(), ManifestMerger2.MergeType.APPLICATION);
        invoker.setOverride(SystemProperty.PACKAGE, mPackageName);
        invoker.setOverride(SystemProperty.MIN_SDK_VERSION,
                String.valueOf(settings.getInt(ModuleSettings.MIN_SDK_VERSION, 21)));
        invoker.setOverride(SystemProperty.TARGET_SDK_VERSION,
                String.valueOf(settings.getInt(ModuleSettings.TARGET_SDK_VERSION, 30)));
        invoker.setOverride(SystemProperty.VERSION_CODE,
                String.valueOf(settings.getInt(ModuleSettings.VERSION_CODE, 1)));
        invoker.setOverride(SystemProperty.VERSION_NAME,
                settings.getString(ModuleSettings.VERSION_NAME, "1.0"));
        if (mLibraryManifestFiles != null) {
            invoker.addLibraryManifests(mLibraryManifestFiles);
        }
        invoker.setVerbose(false);
        try {
            MergingReport report = invoker.merge();
            if (report.getResult().isError()) {
                report.log(getLogger());
                throw new CompilationFailedException(report.getReportString());
            }
            if (report.getMergedDocument().isPresent()) {
                Document document = report.getMergedDocument().get()
                        .getXml();
                // inject the tools namespace, some libraries may use the tools attribute but
                // the main manifest may not have it defined
                document.getDocumentElement()
                        .setAttribute(SdkConstants.XMLNS_PREFIX + SdkConstants.TOOLS_PREFIX,
                                SdkConstants.TOOLS_URI);
                String contents = XmlPrettyPrinter.prettyPrint(document,
                        XmlFormatPreferences.defaults(),
                        XmlFormatStyle.get(document),
                        null,
                        false);
                FileUtils.writeStringToFile(mOutputFile,
                        contents,
                        Charset.defaultCharset());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            throw new CompilationFailedException(e);
        }
    }

    private String getApplicationId() throws IOException {
        String packageName = getModule().getPackageName();
        if (packageName == null) {
            throw new IOException("Failed to parse package name");
        }
        return packageName;
    }
}
