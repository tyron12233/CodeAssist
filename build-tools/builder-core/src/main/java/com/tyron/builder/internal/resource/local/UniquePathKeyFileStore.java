package com.tyron.builder.internal.resource.local;

import org.apache.commons.io.FileUtils;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.internal.hash.ChecksumService;

import java.io.File;

/**
 * Assumes that files do not need to be replaced in the filestore.
 *
 * Can be used as an optimisation if path contains a checksum of the file, as there is no point to perform the replace in that circumstance.
 */
@NonNullApi
public class UniquePathKeyFileStore extends DefaultPathKeyFileStore {

    public UniquePathKeyFileStore(ChecksumService checksumService, File baseDir) {
        super(checksumService, baseDir);
    }

    @Override
    public LocallyAvailableResource move(String path, File source) {
        LocallyAvailableResource entry = super.move(path, source);
        if (source.exists()) {
            FileUtils.deleteQuietly(source);
        }
        return entry;
    }

    @Override
    protected void doAdd(File destination, Action<File> action) {
        if (!destination.exists()) {
            super.doAdd(destination, action);
        }
    }
}
