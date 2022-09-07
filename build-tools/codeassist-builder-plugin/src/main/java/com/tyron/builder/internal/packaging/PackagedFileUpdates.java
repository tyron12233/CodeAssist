package com.tyron.builder.internal.packaging;

import com.android.annotations.NonNull;
import com.tyron.builder.files.RelativeFile;
import com.android.ide.common.resources.FileStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utilities to handle {@link PackagedFileUpdate} objects.
 */
final class PackagedFileUpdates {

    /**
     * Creates a list of {@link PackagedFileUpdate} based on a {@link Map} of {@link RelativeFile}
     * to {@link FileStatus}. The returned list will contain one entry per entry in the input map in
     * a 1-1 match.
     *
     * @param map the incremental relative file set, a {@link Map} of {@link RelativeFile} to {@link
     *     FileStatus}.
     * @return the list of {@link PackagedFileUpdate}
     */
    @NonNull
    static List<PackagedFileUpdate> fromIncrementalRelativeFileSet(
            @NonNull Map<RelativeFile, FileStatus> map) {
        List<PackagedFileUpdate> updates = new ArrayList<>();
        for (Map.Entry<RelativeFile, FileStatus> entry : map.entrySet()) {
            updates.add(
                    new PackagedFileUpdate(
                            entry.getKey(), entry.getKey().getRelativePath(), entry.getValue()));
        }

        return updates;
    }
}
