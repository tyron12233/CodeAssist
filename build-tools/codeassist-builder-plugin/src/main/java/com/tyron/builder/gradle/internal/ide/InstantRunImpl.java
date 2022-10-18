package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.InstantRun;
import java.io.File;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of the {@link InstantRun} model
 */
@Immutable
final class InstantRunImpl implements InstantRun, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final File infoFile;
    private final int supportStatus;

    public InstantRunImpl(@NonNull File infoFile, int supportStatus) {
        this.infoFile = infoFile;
        this.supportStatus = supportStatus;
    }

    @NonNull
    @Override
    public File getInfoFile() {
        return infoFile;
    }

    @Override
    public boolean isSupportedByArtifact() {
        return supportStatus == InstantRun.STATUS_SUPPORTED;
    }

    @Override
    public int getSupportStatus() {
        return supportStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InstantRunImpl that = (InstantRunImpl) o;
        return supportStatus == that.supportStatus &&
                Objects.equals(infoFile, that.infoFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(infoFile, supportStatus);
    }
}
