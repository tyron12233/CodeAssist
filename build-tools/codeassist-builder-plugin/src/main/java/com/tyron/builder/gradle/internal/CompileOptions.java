package com.tyron.builder.gradle.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.NonNullableSetter;
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import java.util.Locale;
import javax.inject.Inject;
import org.gradle.api.JavaVersion;

/** Java compilation options. */
public abstract class CompileOptions implements com.tyron.builder.api.dsl.CompileOptions {
    private static final String VERSION_PREFIX = "VERSION_";

    @Nullable private Boolean incremental = null;

    @Nullable private Boolean coreLibraryDesugaringEnabled = null;

    /** @see #setDefaultJavaVersion(JavaVersion) */
    @NonNull @VisibleForTesting JavaVersion defaultJavaVersion = JavaVersion.VERSION_1_8;

    protected void lazyInit() {
        setEncoding(Charsets.UTF_8.name());
        setSourceCompatibility(defaultJavaVersion);
        setTargetCompatibility(defaultJavaVersion);
    }

    @Inject
    @WithLazyInitialization(methodName = "lazyInit")
    public CompileOptions() {}

    public void setSourceCompatibility(@NonNull Object sourceCompatibility) {
        setSourceCompatibility(convert(sourceCompatibility));
    }

    @Override
    public void sourceCompatibility(@NonNull Object sourceCompatibility) {
        setSourceCompatibility(convert(sourceCompatibility));
    }

    @Override
    @NonNullableSetter
    public abstract void setSourceCompatibility(@NonNull JavaVersion sourceCompatibility);

    @Override
    @NonNull
    public abstract JavaVersion getSourceCompatibility();

    public void setTargetCompatibility(@NonNull Object targetCompatibility) {
        setTargetCompatibility(convert(targetCompatibility));
    }

    @Override
    public void targetCompatibility(@NonNull Object targetCompatibility) {
        setTargetCompatibility(convert(targetCompatibility));
    }

    @Override
    @NonNullableSetter
    public abstract void setTargetCompatibility(@NonNull JavaVersion targetCompatibility);

    @Override
    @NonNull
    public abstract JavaVersion getTargetCompatibility();

    @Override
    @NonNullableSetter
    public abstract void setEncoding(@NonNull String encoding);

    @Override
    @NonNull
    public abstract String getEncoding();

    /**
     * Default java version, based on the target SDK. Set by the plugin, not meant to be used in
     * build files by users.
     */
    public void setDefaultJavaVersion(@NonNull JavaVersion defaultJavaVersion) {
        this.defaultJavaVersion = checkNotNull(defaultJavaVersion);
    }

    /**
     * Whether Java compilation should be incremental or not.
     *
     * <p>The default value is {@code true}.
     *
     * <p>Note that even if this option is set to {@code true}, Java compilation may still be
     * non-incremental (e.g., if incremental annotation processing is not yet possible in the
     * project).
     */
    @Nullable
    public Boolean getIncremental() {
        return incremental;
    }

    /** @see #getIncremental() */
    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    @Nullable
    public Boolean getCoreLibraryDesugaringEnabled() {
        return coreLibraryDesugaringEnabled;
    }

    @Override
    public boolean isCoreLibraryDesugaringEnabled() {
        return coreLibraryDesugaringEnabled != null ? coreLibraryDesugaringEnabled : false;
    }

    @Override
    public void setCoreLibraryDesugaringEnabled(boolean coreLibraryDesugaringEnabled) {
        this.coreLibraryDesugaringEnabled = coreLibraryDesugaringEnabled;
    }

    /**
     * Converts all possible supported way of specifying a Java version to a {@link JavaVersion}.
     * @param version the user provided java version.
     */
    @NonNull
    private static JavaVersion convert(@NonNull Object version) {
        // for backward version reasons, we support setting strings like 'Version_1_6'
        if (version instanceof String) {
            final String versionString = (String) version;
            if (versionString.toUpperCase(Locale.ENGLISH).startsWith(VERSION_PREFIX)) {
                version = versionString.substring(VERSION_PREFIX.length()).replace('_', '.');
            }
        }
        return JavaVersion.toVersion(version);
    }
}