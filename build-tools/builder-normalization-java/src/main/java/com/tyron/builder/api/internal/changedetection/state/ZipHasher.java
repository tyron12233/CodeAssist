package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.file.archive.ZipInput;
import com.tyron.builder.internal.file.archive.impl.FileZipInput;
import com.tyron.builder.internal.file.archive.impl.StreamZipInput;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintHashingStrategy;
import com.tyron.builder.internal.fingerprint.hashing.ConfigurableNormalizer;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContextHasher;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.hashing.ZipEntryContext;
import com.tyron.builder.internal.fingerprint.impl.DefaultFileSystemLocationFingerprint;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ZipHasher implements RegularFileSnapshotContextHasher, ConfigurableNormalizer {

    private static final Set<String> KNOWN_ZIP_EXTENSIONS = ImmutableSet
            .of("zip", "jar", "war", "rar", "ear", "apk", "aar");
    private static final Logger LOGGER = LoggerFactory.getLogger(ZipHasher.class);
    private static final HashCode EMPTY_HASH_MARKER = Hashes.signature(ZipHasher.class);

    public static boolean isZipFile(final String name) {
        return KNOWN_ZIP_EXTENSIONS.contains(FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT));
    }

    private final ResourceHasher resourceHasher;
    private final HashingExceptionReporter hashingExceptionReporter;

    public ZipHasher(ResourceHasher resourceHasher) {
        this(
                resourceHasher,
                (s, e) -> LOGGER.debug("Malformed archive '{}'. Falling back to full content hash instead of entry hashing.", s.getName(), e)
        );
    }

    public ZipHasher(ResourceHasher resourceHasher, HashingExceptionReporter hashingExceptionReporter) {
        this.resourceHasher = resourceHasher;
        this.hashingExceptionReporter = hashingExceptionReporter;
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshotContext fileSnapshotContext) {
        return hashZipContents(fileSnapshotContext.getSnapshot());
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        resourceHasher.appendConfigurationToHasher(hasher);
    }

    @Nullable
    private HashCode hashZipContents(RegularFileSnapshot zipFileSnapshot) {
        try {
            List<FileSystemLocationFingerprint> fingerprints = fingerprintZipEntries(zipFileSnapshot.getAbsolutePath());
            if (fingerprints.isEmpty()) {
                return null;
            }
            Hasher hasher = Hashes.newHasher();
            FingerprintHashingStrategy.SORT.appendToHasher(hasher, fingerprints);
            return hasher.hash();
        } catch (Exception e) {
            hashingExceptionReporter.report(zipFileSnapshot, e);
            return zipFileSnapshot.getHash();
        }
    }

    private List<FileSystemLocationFingerprint> fingerprintZipEntries(String zipFile) throws IOException {
        try (ZipInput input = FileZipInput.create(new File(zipFile))) {
            List<FileSystemLocationFingerprint> fingerprints = Lists.newArrayList();
            fingerprintZipEntries("", zipFile, fingerprints, input);
            return fingerprints;
        }
    }

    private void fingerprintZipEntries(String parentName, String rootParentName, List<FileSystemLocationFingerprint> fingerprints, ZipInput input) throws IOException {
        fingerprints.add(newZipMarker(parentName));
        for (ZipEntry zipEntry : input) {
            if (zipEntry.isDirectory()) {
                continue;
            }
            String fullName = parentName.isEmpty() ? zipEntry.getName() : parentName + "/" + zipEntry.getName();
            ZipEntryContext zipEntryContext = new DefaultZipEntryContext(zipEntry, fullName, rootParentName);
            if (isZipFile(zipEntry.getName())) {
                zipEntryContext.getEntry().withInputStream((ZipEntry.InputStreamAction<Void>) inputStream -> {
                    fingerprintZipEntries(fullName, rootParentName, fingerprints, new StreamZipInput(inputStream));
                    return null;
                });
            } else {
                fingerprintZipEntry(zipEntryContext, fingerprints);
            }
        }
    }

    private void fingerprintZipEntry(ZipEntryContext zipEntryContext, List<FileSystemLocationFingerprint> fingerprints) throws IOException {
        HashCode hash = resourceHasher.hash(zipEntryContext);
        if (hash != null) {
            fingerprints.add(new DefaultFileSystemLocationFingerprint(zipEntryContext.getFullName(), FileType.RegularFile, hash));
        }
    }

    private DefaultFileSystemLocationFingerprint newZipMarker(String relativePath) {
        return new DefaultFileSystemLocationFingerprint(relativePath, FileType.RegularFile, EMPTY_HASH_MARKER);
    }

    public interface HashingExceptionReporter {
        void report(RegularFileSnapshot zipFileSnapshot, Exception e);
    }
}