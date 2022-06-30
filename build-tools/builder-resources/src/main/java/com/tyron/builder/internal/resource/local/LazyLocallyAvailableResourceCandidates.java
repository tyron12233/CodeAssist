package com.tyron.builder.internal.resource.local;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.hash.ChecksumService;

import java.io.File;
import java.util.List;

public class LazyLocallyAvailableResourceCandidates implements LocallyAvailableResourceCandidates {

    private final Factory<List<File>> filesFactory;
    private final ChecksumService checksumService;
    private List<File> files;

    public LazyLocallyAvailableResourceCandidates(Factory<List<File>> filesFactory, ChecksumService checksumService) {
        this.filesFactory = filesFactory;
        this.checksumService = checksumService;
    }

    protected List<File> getFiles() {
        if (files == null) {
            files = filesFactory.create();
        }
        return files;
    }

    @Override
    public boolean isNone() {
        return getFiles().isEmpty();
    }

    @Override
    public LocallyAvailableResource findByHashValue(HashCode targetHash) {
        HashCode thisHash;
        for (File file : getFiles()) {
            thisHash = checksumService.sha1(file);
            if (thisHash.equals(targetHash)) {
                return new DefaultLocallyAvailableResource(file, thisHash);
            }
        }

        return null;
    }

}
