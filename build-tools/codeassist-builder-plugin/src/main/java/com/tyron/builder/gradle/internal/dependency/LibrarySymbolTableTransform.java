package com.tyron.builder.gradle.internal.dependency;

import static com.android.SdkConstants.FN_ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.FN_RESOURCE_TEXT;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.res.LinkApplicationAndroidResourcesTask;
import com.android.ide.common.symbols.SymbolIo;
import com.android.ide.common.symbols.SymbolTable;
import com.android.ide.common.xml.AndroidManifestParser;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.xml.sax.SAXException;

/**
 * Transform that extracts the package name from the manifest and combines it with the r.txt symbol
 * table.
 *
 * <p>This means that one artifact contains all the information needed to build a {@link
 * SymbolTable} for {@link LinkApplicationAndroidResourcesTask}
 */
@CacheableTransform
public abstract class LibrarySymbolTableTransform
        implements TransformAction<GenericTransformParameters> {

    @Classpath
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(@NonNull TransformOutputs transformOutputs) {
        try {
            Path explodedAar = getInputArtifact().get().getAsFile().toPath();
            transform(explodedAar, transformOutputs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void transform(
            @NonNull Path explodedAar, @NonNull TransformOutputs transformOutputs)
            throws IOException {
        Path manifest = explodedAar.resolve(FN_ANDROID_MANIFEST_XML);
        if (!Files.exists(manifest)) {
            return;
        }
        String packageName = getPackageName(manifest);
        // May not exist in some AARs. e.g. the multidex support library.
        Path rTxt = explodedAar.resolve(FN_RESOURCE_TEXT);
        Path outputFile = transformOutputs.file(packageName + "-r.txt").toPath();
        SymbolIo.writeSymbolListWithPackageName(rTxt, packageName, outputFile);
    }

    private static String getPackageName(Path manifest) throws IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(manifest))) {
            return AndroidManifestParser.parse(is).getPackage();
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException(
                    "Failed to get package name from manifest " + manifest.toAbsolutePath(), e);
        }
    }
}
