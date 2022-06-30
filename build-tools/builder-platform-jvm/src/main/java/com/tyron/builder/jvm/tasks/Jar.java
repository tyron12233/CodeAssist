package com.tyron.builder.jvm.tasks;

import com.google.common.collect.ImmutableList;
import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.copy.CopySpecInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.java.archives.Manifest;
import com.tyron.builder.api.java.archives.internal.CustomManifestInternalWrapper;
import com.tyron.builder.api.java.archives.internal.DefaultManifest;
import com.tyron.builder.api.java.archives.internal.ManifestInternal;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.bundling.Zip;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.serialization.Cached;
import com.tyron.builder.util.internal.ConfigureUtil;
import com.tyron.builder.work.DisableCachingByDefault;

import java.nio.charset.Charset;

import static com.tyron.builder.api.internal.lambdas.SerializableLambdas.action;

/**
 * Assembles a JAR archive.
 */
@DisableCachingByDefault(because = "Not worth caching")
public class Jar extends Zip {

    public static final String DEFAULT_EXTENSION = "jar";
    private String manifestContentCharset = DefaultManifest.DEFAULT_CONTENT_CHARSET;
    private Manifest manifest;
    private final CopySpecInternal metaInf;

    public Jar() {
        getArchiveExtension().set(DEFAULT_EXTENSION);
        setMetadataCharset("UTF-8");

        manifest = new DefaultManifest(getFileResolver());
        // Add these as separate specs, so they are not affected by the changes to the main spec
        metaInf = (CopySpecInternal) getRootSpec().addFirst().into("META-INF");
        metaInf.addChild().from(manifestFileTree());
        getMainSpec().appendCachingSafeCopyAction(new ExcludeManifestAction());
    }

    private FileTreeInternal manifestFileTree() {
        final Cached<ManifestInternal> manifest = Cached.of(this::computeManifest);
        final OutputChangeListener outputChangeListener = outputChangeListener();
        return fileCollectionFactory().generated(
            getTemporaryDirFactory(),
            "MANIFEST.MF",
            action(file -> outputChangeListener.beforeOutputChange(ImmutableList.of(file.getAbsolutePath()))),
            action(outputStream -> manifest.get().writeTo(outputStream))
        );
    }

    private ManifestInternal computeManifest() {
        Manifest manifest = getManifest();
        if (manifest == null) {
            manifest = new DefaultManifest(null);
        }
        ManifestInternal manifestInternal;
        if (manifest instanceof ManifestInternal) {
            manifestInternal = (ManifestInternal) manifest;
        } else {
            manifestInternal = new CustomManifestInternalWrapper(manifest);
        }
        manifestInternal.setContentCharset(manifestContentCharset);
        return manifestInternal;
    }

    private FileCollectionFactory fileCollectionFactory() {
        return getServices().get(FileCollectionFactory.class);
    }

    private OutputChangeListener outputChangeListener() {
        return getServices().get(OutputChangeListener.class);
    }

    /**
     * The character set used to encode JAR metadata like file names.
     * Defaults to UTF-8.
     * You can change this property but it is not recommended as JVMs expect JAR metadata to be encoded using UTF-8
     *
     * @return the character set used to encode JAR metadata like file names
     * @since 2.14
     */
    @Override
    public String getMetadataCharset() {
        return super.getMetadataCharset();
    }

    /**
     * The character set used to encode JAR metadata like file names.
     * Defaults to UTF-8.
     * You can change this property but it is not recommended as JVMs expect JAR metadata to be encoded using UTF-8
     *
     * @param metadataCharset the character set used to encode JAR metadata like file names
     * @since 2.14
     */
    @Override
    public void setMetadataCharset(String metadataCharset) {
        super.setMetadataCharset(metadataCharset);
    }

    /**
     * The character set used to encode the manifest content.
     * Defaults to UTF-8.
     * You can change this property but it is not recommended as JVMs expect manifests content to be encoded using UTF-8.
     *
     * @return the character set used to encode the manifest content
     * @since 2.14
     */
    @Input
    public String getManifestContentCharset() {
        return manifestContentCharset;
    }

    /**
     * The character set used to encode the manifest content.
     *
     * @param manifestContentCharset the character set used to encode the manifest content
     * @see #getManifestContentCharset()
     * @since 2.14
     */
    public void setManifestContentCharset(String manifestContentCharset) {
        if (manifestContentCharset == null) {
            throw new InvalidUserDataException("manifestContentCharset must not be null");
        }
        if (!Charset.isSupported(manifestContentCharset)) {
            throw new InvalidUserDataException(String.format("Charset for manifestContentCharset '%s' is not supported by your JVM", manifestContentCharset));
        }
        this.manifestContentCharset = manifestContentCharset;
    }

    /**
     * Returns the manifest for this JAR archive.
     *
     * @return The manifest
     */
    @Internal
    public Manifest getManifest() {
        return manifest;
    }

    /**
     * Sets the manifest for this JAR archive.
     *
     * @param manifest The manifest. May be null.
     */
    public void setManifest(Manifest manifest) {
        this.manifest = manifest;
    }

    /**
     * Configures the manifest for this JAR archive.
     *
     * <p>The given closure is executed to configure the manifest. The {@link com.tyron.builder.api.java.archives.Manifest} is passed to the closure as its delegate.</p>
     *
     * @param configureClosure The closure.
     * @return This.
     */
    public Jar manifest(Closure<?> configureClosure) {
        ConfigureUtil.configure(configureClosure, forceManifest());
        return this;
    }

    /**
     * Configures the manifest for this JAR archive.
     *
     * <p>The given action is executed to configure the manifest.</p>
     *
     * @param configureAction The action.
     * @return This.
     * @since 3.5
     */
    public Jar manifest(Action<? super Manifest> configureAction) {
        configureAction.execute(forceManifest());
        return this;
    }

    private Manifest forceManifest() {
        if (manifest == null) {
            manifest = new DefaultManifest(((ProjectInternal) getProject()).getFileResolver());
        }
        return manifest;
    }

    @Internal
    public CopySpec getMetaInf() {
        return metaInf.addChild();
    }

    /**
     * Adds content to this JAR archive's META-INF directory.
     *
     * <p>The given closure is executed to configure a {@code CopySpec}. The {@link com.tyron.builder.api.file.CopySpec} is passed to the closure as its delegate.</p>
     *
     * @param configureClosure The closure.
     * @return The created {@code CopySpec}
     */
    public CopySpec metaInf(Closure<?> configureClosure) {
        return ConfigureUtil.configure(configureClosure, getMetaInf());
    }

    /**
     * Adds content to this JAR archive's META-INF directory.
     *
     * <p>The given action is executed to configure a {@code CopySpec}.</p>
     *
     * @param configureAction The action.
     * @return The created {@code CopySpec}
     * @since 3.5
     */
    public CopySpec metaInf(Action<? super CopySpec> configureAction) {
        CopySpec metaInf = getMetaInf();
        configureAction.execute(metaInf);
        return metaInf;
    }

    private static class ExcludeManifestAction implements Action<FileCopyDetails> {
        @Override
        public void execute(FileCopyDetails details) {
            if (details.getPath().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                details.exclude();
            }
        }
    }
}
