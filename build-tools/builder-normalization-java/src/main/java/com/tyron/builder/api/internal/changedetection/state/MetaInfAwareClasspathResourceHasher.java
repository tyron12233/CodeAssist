package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.hashing.ZipEntryContext;
import com.tyron.builder.internal.hash.Hashes;

import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static java.lang.String.join;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetaInfAwareClasspathResourceHasher implements ResourceHasher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetaInfAwareClasspathResourceHasher.class);

    private final ResourceHasher delegate;
    private final ResourceEntryFilter attributeResourceFilter;

    public MetaInfAwareClasspathResourceHasher(ResourceHasher delegate, ResourceEntryFilter attributeResourceFilter) {
        this.delegate = delegate;
        this.attributeResourceFilter = attributeResourceFilter;
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        delegate.appendConfigurationToHasher(hasher);
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        attributeResourceFilter.appendConfigurationToHasher(hasher);
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshotContext snapshotContext) throws IOException {
        String relativePath = String.join("/", snapshotContext.getRelativePathSegments().get());
        if (isManifestFile(relativePath)) {
            return tryHashWithFallback(snapshotContext);
        } else {
            return delegate.hash(snapshotContext);
        }
    }

    @Nullable
    @Override
    public HashCode hash(ZipEntryContext zipEntryContext) throws IOException {
        ZipEntry zipEntry = zipEntryContext.getEntry();
        if (isManifestFile(zipEntry.getName())) {
            return tryHashWithFallback(zipEntryContext);
        } else {
            return delegate.hash(zipEntryContext);
        }
    }

    @Nullable
    private HashCode tryHashWithFallback(RegularFileSnapshotContext snapshotContext) throws IOException {
        try (FileInputStream manifestFileInputStream = new FileInputStream(snapshotContext.getSnapshot().getAbsolutePath())) {
            return hashManifest(manifestFileInputStream);
        } catch (IOException e) {
            LOGGER.debug("Could not load fingerprint for " + snapshotContext.getSnapshot().getAbsolutePath() + ". Falling back to full entry fingerprinting", e);
            return delegate.hash(snapshotContext);
        }
    }

    @Nullable
    private HashCode tryHashWithFallback(ZipEntryContext zipEntryContext) throws IOException {
        try {
            return zipEntryContext.getEntry().withInputStream(this::hashManifest);
        } catch (IOException e) {
            LOGGER.debug("Could not load fingerprint for " + zipEntryContext.getRootParentName() + "!" + zipEntryContext.getFullName() + ". Falling back to full entry fingerprinting", e);
            return delegate.hash(zipEntryContext);
        }
    }

    private static boolean isManifestFile(final String name) {
        return name.equals("META-INF/MANIFEST.MF");
    }

    private HashCode hashManifest(InputStream inputStream) throws IOException {
        Manifest manifest = new Manifest(inputStream);
        Hasher hasher = Hashes.newHasher();
        Attributes mainAttributes = manifest.getMainAttributes();
        hashManifestAttributes(mainAttributes, "main", hasher);
        Map<String, Attributes> entries = manifest.getEntries();
        Set<String> names = new TreeSet<>(manifest.getEntries().keySet());
        for (String name : names) {
            hashManifestAttributes(entries.get(name), name, hasher);
        }
        return hasher.hash();
    }

    private void hashManifestAttributes(Attributes attributes, String name, Hasher hasher) {
        Map<String, String> entries = attributes
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString().toLowerCase(Locale.ROOT),
                        entry -> (String) entry.getValue()
                ));
        List<Map.Entry<String, String>> normalizedEntries = entries.
                entrySet()
                .stream()
                .filter(entry -> !attributeResourceFilter.shouldBeIgnored(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        // Short-circuiting when there's no matching entries allows empty manifest sections to be ignored
        // that allows an manifest without those sections to hash identically to the one with effectively empty sections
        if (!normalizedEntries.isEmpty()) {
            hasher.putString(name, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : normalizedEntries) {
                hasher.putString(entry.getKey(), StandardCharsets.UTF_8);
                hasher.putString(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
    }
}