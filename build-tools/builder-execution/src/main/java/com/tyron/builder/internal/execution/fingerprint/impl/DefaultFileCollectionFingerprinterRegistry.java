package com.tyron.builder.internal.execution.fingerprint.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinterRegistry;
import com.tyron.builder.internal.execution.fingerprint.FileNormalizationSpec;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DefaultFileCollectionFingerprinterRegistry implements FileCollectionFingerprinterRegistry {
    private final Map<FileNormalizationSpec, FileCollectionFingerprinter> fingerprinters;

    public DefaultFileCollectionFingerprinterRegistry(Collection<FingerprinterRegistration> registrations) {
        this.fingerprinters = ImmutableMap.copyOf(entriesFrom(registrations));
    }

    private List<Map.Entry<FileNormalizationSpec, FileCollectionFingerprinter>> entriesFrom(Collection<FingerprinterRegistration> registrations) {
        return registrations.stream().map(registration -> Maps
                .immutableEntry(registration.getSpec(), registration.getFingerprinter())).collect(
                ImmutableList.toImmutableList());
    }

    @Override
    public FileCollectionFingerprinter getFingerprinter(FileNormalizationSpec spec) {
        FileCollectionFingerprinter fingerprinter = fingerprinters.get(spec);
        if (fingerprinter == null) {
            throw new IllegalStateException(String.format("No fingerprinter registered with type '%s', directory sensitivity '%s' and line ending normalization '%s'", spec.getNormalizer().getName(), spec.getDirectorySensitivity().name(), spec.getLineEndingNormalization()));
        }
        return fingerprinter;
    }
}