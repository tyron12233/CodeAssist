package com.tyron.builder.gradle.internal.ide;

import static com.android.SdkConstants.FD_AIDL;
import static com.android.SdkConstants.FD_ASSETS;
import static com.android.SdkConstants.FD_JARS;
import static com.android.SdkConstants.FD_JNI;
import static com.android.SdkConstants.FD_RENDERSCRIPT;
import static com.android.SdkConstants.FD_RES;
import static com.android.SdkConstants.FN_ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.FN_ANNOTATIONS_ZIP;
import static com.android.SdkConstants.FN_API_JAR;
import static com.android.SdkConstants.FN_CLASSES_JAR;
import static com.android.SdkConstants.FN_LINT_JAR;
import static com.android.SdkConstants.FN_PROGUARD_TXT;
import static com.android.SdkConstants.FN_PUBLIC_TXT;
import static com.android.SdkConstants.FN_RESOURCE_STATIC_LIBRARY;
import static com.android.SdkConstants.FN_RESOURCE_TEXT;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.AndroidLibrary;
import com.tyron.builder.model.JavaLibrary;
import com.tyron.builder.model.MavenCoordinates;
import com.android.utils.FileUtils;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** Serializable implementation of AndroidLibrary for use in the model. */
@Immutable
public final class AndroidLibraryImpl extends LibraryImpl implements AndroidLibrary, Serializable {
    private static final long serialVersionUID = 1L;

    @Nullable
    private final String variant;
    @NonNull private final File bundle;
    @NonNull private final File folder;
    @NonNull
    private final List<AndroidLibrary> androidLibraries;
    @NonNull
    private final Collection<JavaLibrary> javaLibraries;
    @NonNull
    private final Collection<File> localJars;
    @Nullable private final File lintJar;

    private final int hashcode;

    public AndroidLibraryImpl(
            @NonNull MavenCoordinates coordinates,
            @Nullable String buildId,
            @Nullable String projectPath,
            @NonNull File bundle,
            @NonNull File extractedFolder,
            @Nullable String variant,
            boolean isProvided,
            boolean isSkipped,
            @NonNull List<AndroidLibrary> androidLibraries,
            @NonNull Collection<JavaLibrary> javaLibraries,
            @NonNull Collection<File> localJavaLibraries,
            @Nullable File lintJar) {
        super(buildId, projectPath, null, coordinates, isSkipped, isProvided);
        this.androidLibraries = ImmutableList.copyOf(androidLibraries);
        this.javaLibraries = ImmutableList.copyOf(javaLibraries);
        this.localJars = ImmutableList.copyOf(localJavaLibraries);
        this.variant = variant;
        this.bundle = bundle;
        this.folder = extractedFolder;
        this.lintJar = lintJar;
        hashcode = computeHashCode();
    }

    @Nullable
    @Override
    public String getProjectVariant() {
        return variant;
    }

    @NonNull
    @Override
    public File getBundle() {
        return bundle;
    }

    @NonNull
    @Override
    public File getFolder() {
        return folder;
    }

    @NonNull
    @Override
    public List<? extends AndroidLibrary> getLibraryDependencies() {
        return androidLibraries;
    }

    @NonNull
    @Override
    public Collection<? extends JavaLibrary> getJavaDependencies() {
        return javaLibraries;
    }

    @NonNull
    @Override
    public Collection<File> getLocalJars() {
        return localJars;
    }

    @NonNull
    @Override
    public File getManifest() {
        return new File(folder, FN_ANDROID_MANIFEST_XML);
    }

    @NonNull
    @Override
    public File getJarFile() {
        return FileUtils.join(folder, FD_JARS, FN_CLASSES_JAR);
    }

    @NonNull
    @Override
    public File getCompileJarFile() {
        // We use the api.jar file for compiling if that file exists (api.jar is optional in an
        // AAR); otherwise, we use the regular jar file for compiling.
        File apiJarFile = FileUtils.join(folder, FN_API_JAR);
        return apiJarFile.exists() ? apiJarFile : getJarFile();
    }

    @NonNull
    @Override
    public File getResFolder() {
        return new File(folder, FD_RES);
    }

    @NonNull
    @Override
    public File getResStaticLibrary() {
        return new File(folder, FN_RESOURCE_STATIC_LIBRARY);
    }

    @NonNull
    @Override
    public File getAssetsFolder() {
        return new File(folder, FD_ASSETS);
    }

    @NonNull
    @Override
    public File getJniFolder() {
        return new File(folder, FD_JNI);
    }

    @NonNull
    @Override
    public File getAidlFolder() {
        return new File(folder, FD_AIDL);
    }


    @NonNull
    @Override
    public File getRenderscriptFolder() {
        return new File(folder, FD_RENDERSCRIPT);
    }

    @NonNull
    @Override
    public File getProguardRules() {
        return new File(folder, FN_PROGUARD_TXT);
    }

    @NonNull
    @Override
    public File getLintJar() {
        if (lintJar == null) {
            return new File(getJarFile().getParentFile(), FN_LINT_JAR);
        } else {
            return lintJar;
        }
    }


    @NonNull
    @Override
    public File getExternalAnnotations() {
        return new File(folder, FN_ANNOTATIONS_ZIP);
    }


    @Override
    @NonNull
    public File getPublicResources() {
        return new File(folder, FN_PUBLIC_TXT);
    }


    @Override
    @Deprecated
    public boolean isOptional() {
        return isProvided();
    }

    @NonNull
    @Override
    public File getSymbolFile() {
        return new File(folder, FN_RESOURCE_TEXT);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AndroidLibraryImpl that = (AndroidLibraryImpl) o;

        // quick fail on hashcode to avoid comparing the whole tree
        if (hashcode != that.hashcode || !super.equals(o)) {
            return false;
        }

        return Objects.equal(variant, that.variant)
                && Objects.equal(bundle, that.bundle)
                && Objects.equal(folder, that.folder)
                && Objects.equal(androidLibraries, that.androidLibraries)
                && Objects.equal(javaLibraries, that.javaLibraries)
                && Objects.equal(localJars, that.localJars)
                && Objects.equal(lintJar, that.lintJar);
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    private int computeHashCode() {
        return Objects.hashCode(
                super.hashCode(),
                variant,
                bundle,
                folder,
                androidLibraries,
                javaLibraries,
                localJars,
                lintJar);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("project", getProject())
                .add("variant", variant)
                .add("bundle", bundle)
                .add("folder", folder)
                .add("androidLibraries", androidLibraries)
                .add("javaLibraries", javaLibraries)
                .add("localJars", localJars)
                .add("lintJar", lintJar)
                .add("super", super.toString())
                .toString();
    }
}
