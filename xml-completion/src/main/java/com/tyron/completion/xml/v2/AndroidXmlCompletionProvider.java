package com.tyron.completion.xml.v2;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.Decompress;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository;
import com.tyron.completion.xml.v2.handler.AndroidLayoutHandlerKt;
import com.tyron.completion.xml.v2.handler.AndroidManifestHandlerKt;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;
import java.util.Objects;

public class AndroidXmlCompletionProvider extends CompletionProvider {

    private static final Key<FrameworkResourceRepository> FRAMEWORK_RESOURCE_REPOSITORY_KEY =
            Key.create("frameworkRepository");

    private static final String LAYOUT_ATTRIBUTE_PREFIX = "layout_";

    private static final String NAMESPACE_PREFIX = "xmlns";
    private static final String[] AVAILABLE_NAMESPACES =
            new String[]{"android=\"http://schemas.android.com/apk/res/android\"",
                    "app=\"http://schemas.android.com/apk/res-auto\"",
                    "tools=\"http://schemas.android.com/tools\"",};

    public AndroidXmlCompletionProvider() {

    }

    /**
     * Extracts the framework resources if needed
     *
     * @return the framework resources directory
     */
    private static File getOrExtractFiles() {
        File filesDir = ApplicationProvider.getApplicationContext().getFilesDir();
        File check = new File(filesDir, "sources/android-31/data/res/values/attrs.xml");
        if (check.exists()) {
            return check;
        }
        File dest = new File(filesDir, "sources");
        Decompress.unzipFromAssets(ApplicationProvider.getApplicationContext(),
                "android-xml.zip",
                dest.getAbsolutePath());
        return check;
    }

    @Override
    public boolean accept(File file) {
        return file.exists() && file.getName().endsWith(".xml");
    }

    @Override
    public CompletionList complete(CompletionParameters parameters) {
        Module module = parameters.getModule();
        if (!(module instanceof AndroidModule)) {
            return CompletionList.EMPTY;
        }

        XmlFileType fileType = getFileType(parameters.getFile());
        if (fileType == XmlFileType.UNKNOWN) {
            return CompletionList.EMPTY;
        }

        FrameworkResourceRepository frameworkResourceRepository =
                getFrameworkResourceRepository(module);

        if (fileType == XmlFileType.MANIFEST) {
            return AndroidManifestHandlerKt.handleManifest(frameworkResourceRepository, parameters);
        } else if (fileType == XmlFileType.LAYOUT) {
            return AndroidLayoutHandlerKt.handleLayout(frameworkResourceRepository, parameters);
        }
        return CompletionList.EMPTY;
    }

    private FrameworkResourceRepository getFrameworkResourceRepository(Module module) {
        FrameworkResourceRepository repository =
                module.getUserData(FRAMEWORK_RESOURCE_REPOSITORY_KEY);
        if (repository == null) {
            File extractedDir = getOrExtractFiles();
            File resDirectory =
                    Objects.requireNonNull(extractedDir.getParentFile()).getParentFile();
            repository = FrameworkResourceRepository.create(resDirectory.toPath(),
                    ImmutableSet.of("en"),
                    null,
                    true);
            module.putUserData(FRAMEWORK_RESOURCE_REPOSITORY_KEY, repository);
        }
        return repository;
    }

    private XmlFileType getFileType(File file) {
        if (file.getName().equals("AndroidManifest.xml")) {
            return XmlFileType.MANIFEST;
        } else if (AndroidResourcesUtils.isLayoutXMLFile(file)) {
            return XmlFileType.LAYOUT;
        }
        return XmlFileType.UNKNOWN;
    }
}
