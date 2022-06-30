package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.hashing.ZipEntryContext;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.internal.normalization.java.ApiClassExtractor;

import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

public class AbiExtractingClasspathResourceHasher implements ResourceHasher {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbiExtractingClasspathResourceHasher.class);

    private final ApiClassExtractor extractor;

    public AbiExtractingClasspathResourceHasher() {
        this(new ApiClassExtractor(Collections.emptySet()));
    }

    public AbiExtractingClasspathResourceHasher(ApiClassExtractor extractor) {
        this.extractor = extractor;
    }

    @Nullable
    private HashCode hashClassBytes(byte[] classBytes) {
        // Use the ABI as the hash
        ClassReader reader = new ClassReader(classBytes);
        return extractor.extractApiClassFrom(reader)
                .map(Hashes::hashBytes)
                .orElse(null);
    }

    @Nullable
    @Override
    public HashCode hash(RegularFileSnapshotContext fileSnapshotContext) {
        RegularFileSnapshot fileSnapshot = fileSnapshotContext.getSnapshot();
        try {
            if (!isClassFile(fileSnapshot.getName())) {
                return null;
            }

            Path path = Paths.get(fileSnapshot.getAbsolutePath());
            byte[] classBytes = Files.readAllBytes(path);
            return hashClassBytes(classBytes);
        } catch (Exception e) {
            LOGGER.debug("Malformed class file '{}' found on compile classpath. Falling back to full file hash instead of ABI hashing.", fileSnapshot.getName(), e);
            return fileSnapshot.getHash();
        }
    }

    @Override
    public HashCode hash(ZipEntryContext zipEntryContext) throws IOException {
        ZipEntry zipEntry = zipEntryContext.getEntry();
        if (!isClassFile(zipEntry.getName())) {
            return null;
        }
        byte[] content = zipEntry.getContent();
        return hashClassBytes(content);
    }

    private boolean isClassFile(String name) {
        return name.endsWith(".class");
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
        extractor.appendConfigurationToHasher(hasher);
    }
}