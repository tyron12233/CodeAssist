package com.tyron.builder.internal.fingerprint.impl;


import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;

import java.util.Comparator;

public class IgnoredPathFileSystemLocationFingerprint implements FileSystemLocationFingerprint {

    public static final IgnoredPathFileSystemLocationFingerprint DIRECTORY = new IgnoredPathFileSystemLocationFingerprint(FileType.Directory, FileSystemLocationFingerprint.DIR_SIGNATURE);
    private static final IgnoredPathFileSystemLocationFingerprint MISSING_FILE = new IgnoredPathFileSystemLocationFingerprint(FileType.Missing, FileSystemLocationFingerprint.MISSING_FILE_SIGNATURE);

    private final FileType type;
    private final HashCode normalizedContentHash;

    public static IgnoredPathFileSystemLocationFingerprint create(FileType type, HashCode contentHash) {
        switch (type) {
            case Directory:
                return DIRECTORY;
            case Missing:
                return MISSING_FILE;
            case RegularFile:
                return new IgnoredPathFileSystemLocationFingerprint(FileType.RegularFile, contentHash);
            default:
                throw new IllegalStateException();
        }
    }

    private IgnoredPathFileSystemLocationFingerprint(FileType type, HashCode normalizedContentHash) {
        this.type = type;
        this.normalizedContentHash = normalizedContentHash;
    }

    @Override
    public String getNormalizedPath() {
        return "";
    }

    @Override
    public HashCode getNormalizedContentHash() {
        return normalizedContentHash;
    }

    @Override
    public FileType getType() {
        return type;
    }

    @Override
    public int compareTo(FileSystemLocationFingerprint o) {
        if (!(o instanceof IgnoredPathFileSystemLocationFingerprint)) {
            return -1;
        }
        return Comparator.comparingInt(HashCode::hashCode).compare(normalizedContentHash, o.getNormalizedContentHash());
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putBytes(normalizedContentHash.asBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IgnoredPathFileSystemLocationFingerprint that = (IgnoredPathFileSystemLocationFingerprint) o;
        return normalizedContentHash.equals(that.normalizedContentHash);
    }

    @Override
    public int hashCode() {
        return normalizedContentHash.hashCode();
    }

    @Override
    public String toString() {
        return String.format("IGNORED / %s", getType() == FileType.Directory ? "DIR" : getType() == FileType.Missing ? "MISSING" : normalizedContentHash);
    }
}