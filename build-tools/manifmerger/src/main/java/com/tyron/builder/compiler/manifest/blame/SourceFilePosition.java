package com.tyron.builder.compiler.manifest.blame;

import org.jetbrains.annotations.NotNull;

import com.google.common.base.Objects;
import java.io.File;
import java.io.Serializable;

import com.google.errorprone.annotations.Immutable;

@Immutable
public final class SourceFilePosition implements Serializable {

    public static final SourceFilePosition UNKNOWN =
            new SourceFilePosition(SourceFile.UNKNOWN, SourcePosition.UNKNOWN);

    @NotNull
    private final SourceFile mSourceFile;

    @NotNull
    private final SourcePosition mSourcePosition;

    public SourceFilePosition(@NotNull SourceFile sourceFile,
                              @NotNull SourcePosition sourcePosition) {
        mSourceFile = sourceFile;
        mSourcePosition = sourcePosition;
    }

    public SourceFilePosition(@NotNull File file,
                              @NotNull SourcePosition sourcePosition) {
        this(new SourceFile(file), sourcePosition);
    }

    @NotNull
    public SourcePosition getPosition() {
        return mSourcePosition;
    }

    @NotNull
    public SourceFile getFile() {
        return mSourceFile;
    }

    @NotNull
    @Override
    public String toString() {
        return print(false);
    }

    @NotNull
    public String print(boolean shortFormat) {
        if (mSourcePosition.equals(SourcePosition.UNKNOWN)) {
            return mSourceFile.print(shortFormat);
        } else {
            return mSourceFile.print(shortFormat) + ':' + mSourcePosition.toString();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mSourceFile, mSourcePosition);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SourceFilePosition)) {
            return false;
        }
        SourceFilePosition other = (SourceFilePosition) obj;
        return Objects.equal(mSourceFile, other.mSourceFile) &&
                Objects.equal(mSourcePosition, other.mSourcePosition);
    }
}
