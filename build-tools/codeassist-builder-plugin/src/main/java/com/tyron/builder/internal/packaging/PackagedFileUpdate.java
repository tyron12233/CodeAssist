package com.tyron.builder.internal.packaging;

import com.android.annotations.NonNull;
import com.tyron.builder.files.RelativeFile;
import com.android.ide.common.resources.FileStatus;
import com.google.common.base.Objects;


/**
 * Elementary update of a file in a package.
 *
 * <p>An update of a file is either a create, remove or update (as specified in {@link FileStatus})
 * of a single file in a package. The update refers the {@link RelativeFile} the data comes from
 * -- the source. The source may, however, no longer exist if the update is a delete.
 *
 * <p>The update also contains the file name in the archive, which may differ from the file name
 * in the actual file, or path in zip. This will happen, for example, for dex files. Dex files
 * commonly have different names in the file system and inside the apk.
 */
class PackagedFileUpdate {

    /**
     * The source. May not exist if {@link #mStatus} is {@link FileStatus#REMOVED}.
     */
    @NonNull
    private final RelativeFile mSource;

    /**
     * The name of file file in the package.
     */
    @NonNull
    private final String mName;

    /**
     * The update status.
     */
    @NonNull
    private final FileStatus mStatus;


    /**
     * Creates a new update.
     *
     * @param source the source of the file
     * @param name the name of the file in the package
     * @param status the type of update
     */
    PackagedFileUpdate(@NonNull RelativeFile source, @NonNull String name,
            @NonNull FileStatus status) {
        mSource = source;
        mName = name;
        mStatus = status;
    }

    /**
     * Obtains the source.
     *
     * @return the source
     */
    @NonNull
    public RelativeFile getSource() {
        return mSource;
    }

    /**
     * Obtains the name of the file in the package.
     *
     * @return the name of the file
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Obtains the change type.
     *
     * @return how this file changed
     */
    @NonNull
    public FileStatus getStatus() {
        return mStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PackagedFileUpdate that = (PackagedFileUpdate) o;
        return Objects.equal(mSource, that.mSource) &&
                Objects.equal(mName, that.mName) &&
                mStatus == that.mStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mSource, mName, mStatus);
    }
}
