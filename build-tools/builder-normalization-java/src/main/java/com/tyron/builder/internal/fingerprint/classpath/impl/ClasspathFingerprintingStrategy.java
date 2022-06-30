package com.tyron.builder.internal.fingerprint.classpath.impl;

import static com.tyron.builder.internal.fingerprint.classpath.impl.ClasspathFingerprintingStrategy.NonJarFingerprintingStrategy.IGNORE;
import static com.tyron.builder.internal.fingerprint.classpath.impl.ClasspathFingerprintingStrategy.NonJarFingerprintingStrategy.USE_FILE_HASH;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.RelativePathSupplier;
import com.tyron.builder.api.internal.changedetection.state.DefaultRegularFileSnapshotContext;
import com.tyron.builder.api.internal.changedetection.state.IgnoringResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.LineEndingNormalizingResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.MetaInfAwareClasspathResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.PropertiesFileAwareClasspathResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.ResourceEntryFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.changedetection.state.RuntimeClasspathResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.ZipHasher;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.FingerprintHashingStrategy;
import com.tyron.builder.internal.fingerprint.FingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.impl.AbstractFingerprintingStrategy;
import com.tyron.builder.internal.fingerprint.impl.DefaultFileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.impl.IgnoredPathFileSystemLocationFingerprint;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot.FileSystemLocationSnapshotVisitor;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.internal.snapshot.RelativePathTracker;
import com.tyron.builder.internal.snapshot.RelativePathTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;

/**
 * Fingerprints classpath-like file collections.
 *
 * <p>
 * This strategy uses a {@link ResourceHasher} to normalize the contents of files and a {@link ResourceFilter} to ignore resources in classpath entries. Zip files are treated as if the contents would be expanded on disk.
 * </p>
 *
 * <p>
 * The order of the entries in the classpath matters, paths do not matter for the entries.
 * For the resources in each classpath entry, normalization takes the relative path of the resource and possibly normalizes its contents.
 * </p>
 */
public class ClasspathFingerprintingStrategy extends AbstractFingerprintingStrategy {
    private final NonJarFingerprintingStrategy nonZipFingerprintingStrategy;
    private final ResourceSnapshotterCacheService cacheService;
    private final ResourceHasher classpathResourceHasher;
    private final ZipHasher zipHasher;
    private final Interner<String> stringInterner;
    private final HashCode zipHasherConfigurationHash;

    private ClasspathFingerprintingStrategy(
            String identifier,
            NonJarFingerprintingStrategy nonZipFingerprintingStrategy,
            ResourceHasher classpathResourceHasher,
            ZipHasher zipHasher,
            ResourceSnapshotterCacheService cacheService,
            Interner<String> stringInterner
    ) {
        super(identifier, zipHasher);
        this.nonZipFingerprintingStrategy = nonZipFingerprintingStrategy;
        this.classpathResourceHasher = classpathResourceHasher;
        this.cacheService = cacheService;
        this.stringInterner = stringInterner;
        this.zipHasher = zipHasher;

        Hasher hasher = Hashes.newHasher();
        zipHasher.appendConfigurationToHasher(hasher);
        this.zipHasherConfigurationHash = hasher.hash();
    }

    public static ClasspathFingerprintingStrategy runtimeClasspath(
            ResourceFilter classpathResourceFilter,
            ResourceEntryFilter manifestAttributeResourceEntryFilter,
            Map<String, ResourceEntryFilter> propertiesFileFilters,
            RuntimeClasspathResourceHasher runtimeClasspathResourceHasher,
            ResourceSnapshotterCacheService cacheService,
            Interner<String> stringInterner,
            LineEndingSensitivity lineEndingSensitivity
    ) {
        ResourceHasher resourceHasher = LineEndingNormalizingResourceHasher
                .wrap(runtimeClasspathResourceHasher, lineEndingSensitivity);
        resourceHasher = propertiesFileHasher(resourceHasher, propertiesFileFilters);
        resourceHasher = metaInfAwareClasspathResourceHasher(resourceHasher, manifestAttributeResourceEntryFilter);
        resourceHasher = ignoringResourceHasher(resourceHasher, classpathResourceFilter);
        ZipHasher zipHasher = new ZipHasher(resourceHasher);
        return new ClasspathFingerprintingStrategy(FingerprintingStrategy.CLASSPATH_IDENTIFIER, USE_FILE_HASH, resourceHasher, zipHasher, cacheService, stringInterner);
    }

    public static ClasspathFingerprintingStrategy compileClasspath(ResourceHasher classpathResourceHasher, ResourceSnapshotterCacheService cacheService, Interner<String> stringInterner) {
        ZipHasher zipHasher = new ZipHasher(classpathResourceHasher);
        return new ClasspathFingerprintingStrategy(
                FingerprintingStrategy.COMPILE_CLASSPATH_IDENTIFIER, IGNORE, classpathResourceHasher, zipHasher, cacheService, stringInterner);
    }

    public static ClasspathFingerprintingStrategy compileClasspath(ResourceHasher classpathResourceHasher, ResourceSnapshotterCacheService cacheService, Interner<String> stringInterner, ZipHasher.HashingExceptionReporter hashingExceptionReporter) {
        ZipHasher zipHasher = new ZipHasher(classpathResourceHasher, hashingExceptionReporter);
        return new ClasspathFingerprintingStrategy(
                FingerprintingStrategy.COMPILE_CLASSPATH_IDENTIFIER, IGNORE, classpathResourceHasher, zipHasher, cacheService, stringInterner);
    }

    private static ResourceHasher ignoringResourceHasher(ResourceHasher delegate, ResourceFilter resourceFilter) {
        return new IgnoringResourceHasher(delegate, resourceFilter);
    }

    private static ResourceHasher propertiesFileHasher(ResourceHasher delegate, Map<String, ResourceEntryFilter> propertiesFileFilters) {
        return new PropertiesFileAwareClasspathResourceHasher(delegate, propertiesFileFilters);
    }

    private static ResourceHasher metaInfAwareClasspathResourceHasher(ResourceHasher delegate, ResourceEntryFilter manifestAttributeResourceEntryFilter) {
        return new MetaInfAwareClasspathResourceHasher(delegate, manifestAttributeResourceEntryFilter);
    }

    @Override
    public Map<String, FileSystemLocationFingerprint> collectFingerprints(FileSystemSnapshot roots) {
        ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder = ImmutableMap.builder();
        HashSet<String> processedEntries = new HashSet<>();
        roots.accept(new RelativePathTracker(), new ClasspathFingerprintingVisitor(processedEntries, builder));
        return builder.build();
    }

    public enum NonJarFingerprintingStrategy {
        IGNORE {
            @Nullable
            @Override
            public HashCode determineNonJarFingerprint(HashCode original) {
                return null;
            }
        },
        USE_FILE_HASH {
            @Override
            public HashCode determineNonJarFingerprint(HashCode original) {
                return original;
            }
        };

        @Nullable
        public abstract HashCode determineNonJarFingerprint(HashCode original);
    }

    private class ClasspathFingerprintingVisitor implements RelativePathTrackingFileSystemSnapshotHierarchyVisitor {
        private final HashSet<String> processedEntries;
        private final ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder;


        public ClasspathFingerprintingVisitor(HashSet<String> processedEntries, ImmutableMap.Builder<String, FileSystemLocationFingerprint> builder) {
            this.processedEntries = processedEntries;
            this.builder = builder;
        }

        @Override
        public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, RelativePathSupplier relativePath) {
            snapshot.accept(new FileSystemLocationSnapshotVisitor() {
                @Override
                public void visitRegularFile(RegularFileSnapshot fileSnapshot) {
                    HashCode normalizedContentHash = hashContent(fileSnapshot, relativePath);
                    if (normalizedContentHash == null) {
                        return;
                    }

                    String absolutePath = snapshot.getAbsolutePath();
                    if (!processedEntries.add(absolutePath)) {
                        return;
                    }

                    FileSystemLocationFingerprint fingerprint;
                    if (relativePath.isRoot()) {
                        fingerprint = IgnoredPathFileSystemLocationFingerprint
                                .create(snapshot.getType(), normalizedContentHash);
                    } else {
                        String internedRelativePath = stringInterner.intern(relativePath.toRelativePath());
                        fingerprint = new DefaultFileSystemLocationFingerprint(internedRelativePath, FileType.RegularFile, normalizedContentHash);
                    }
                    builder.put(absolutePath, fingerprint);
                }

                @Override
                public void visitMissing(MissingFileSnapshot missingSnapshot) {
                    if (!relativePath.isRoot()) {
                        throw new RuntimeException(String.format("Couldn't read file content: '%s'.", missingSnapshot.getAbsolutePath()));
                    }
                }
            });
            return SnapshotVisitResult.CONTINUE;
        }

        /**
         * Returns either the normalized content hash of the given regular file,
         * or {@code null} if a resource filter has filtered the file out.
         */
        @Nullable
        private HashCode hashContent(RegularFileSnapshot fileSnapshot, RelativePathSupplier relativePath) {
            RegularFileSnapshotContext fileSnapshotContext = new DefaultRegularFileSnapshotContext(() -> Iterables
                    .toArray(relativePath.getSegments(), String.class), fileSnapshot);
            try {
                if (ZipHasher.isZipFile(fileSnapshotContext.getSnapshot().getName())) {
                    return cacheService.hashFile(fileSnapshotContext, zipHasher, zipHasherConfigurationHash);
                } else if (relativePath.isRoot()) {
                    return nonZipFingerprintingStrategy.determineNonJarFingerprint(fileSnapshot.getHash());
                } else {
                    return classpathResourceHasher.hash(fileSnapshotContext);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(failedToNormalize(fileSnapshot), e);
            } catch (UncheckedIOException e) {
                throw new UncheckedIOException(failedToNormalize(fileSnapshot), e.getCause());
            }
        }

        private String failedToNormalize(RegularFileSnapshot snapshot) {
            return String.format("Failed to normalize content of '%s'.", snapshot.getAbsolutePath());
        }
    }

    @Override
    public FingerprintHashingStrategy getHashingStrategy() {
        return FingerprintHashingStrategy.KEEP_ORDER;
    }

    @Override
    public String normalizePath(FileSystemLocationSnapshot snapshot) {
        return "";
    }
}