package org.gradle.api.internal.changedetection.state;

import org.gradle.internal.file.FilePathUtil;
import org.gradle.internal.file.archive.ZipEntry;
import org.gradle.internal.fingerprint.hashing.ZipEntryContext;

import java.util.function.Supplier;

public class DefaultZipEntryContext implements ZipEntryContext {
    private final ZipEntry entry;
    private final String fullName;
    private final String rootParentName;

    public DefaultZipEntryContext(ZipEntry entry, String fullName, String rootParentName) {
        this.entry = entry;
        this.fullName = fullName;
        this.rootParentName = rootParentName;
    }

    @Override
    public ZipEntry getEntry() {
        return entry;
    }

    @Override
    public String getFullName() {
        return fullName;
    }

    @Override
    public String getRootParentName() {
        return rootParentName;
    }

    @Override
    public Supplier<String[]> getRelativePathSegments() {
        return new ZipEntryRelativePath(entry);
    }

    private static class ZipEntryRelativePath implements Supplier<String[]> {
        private final ZipEntry zipEntry;

        ZipEntryRelativePath(ZipEntry zipEntry) {
            this.zipEntry = zipEntry;
        }

        @Override
        public String[] get() {
            return FilePathUtil.getPathSegments(zipEntry.getName());
        }
    }
}
