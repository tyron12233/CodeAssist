package com.tyron.builder.internal.fingerprint.impl;


import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.tyron.builder.internal.execution.fingerprint.impl.FingerprinterRegistration.registration;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.changedetection.state.CachingFileSystemLocationSnapshotHasher;
import com.tyron.builder.api.internal.changedetection.state.LineEndingNormalizingFileSystemLocationSnapshotHasher;
import com.tyron.builder.api.internal.changedetection.state.ResourceEntryFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.execution.fingerprint.impl.FingerprinterRegistration;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.api.internal.cache.StringInterner;

public class FileCollectionFingerprinterRegistrations {
    private final Set<FingerprinterRegistration> registrants;

    public FileCollectionFingerprinterRegistrations(
            StringInterner stringInterner,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            ResourceSnapshotterCacheService resourceSnapshotterCacheService,
            ResourceFilter resourceFilter,
            ResourceEntryFilter metaInfFilter,
            Map<String, ResourceEntryFilter> propertiesFileFilters
    ) {

        List<? extends FileCollectionFingerprinter> insensitiveFingerprinters = insensitiveFingerprinters(resourceSnapshotterCacheService, fileCollectionSnapshotter, stringInterner);
        this.registrants =
                withAllLineEndingSensitivities(lineEndingSensitivity -> {
                    FileSystemLocationSnapshotHasher normalizedContentHasher = normalizedContentHasher(lineEndingSensitivity, resourceSnapshotterCacheService);

                    List<? extends FileCollectionFingerprinter> directoryInsensitiveFingerprinters = directoryInsensitiveFingerprinters(
                            lineEndingSensitivity,
                            normalizedContentHasher,
                            fileCollectionSnapshotter,
                            resourceSnapshotterCacheService,
                            resourceFilter,
                            metaInfFilter,
                            propertiesFileFilters,
                            stringInterner
                    );

                    return withAllDirectorySensitivities(directorySensitivity ->
                            registrationsFor(
                                    lineEndingSensitivity,
                                    directorySensitivity,
                                    Stream.of(
                                            fullySensitiveFingerprinters(
                                                    directorySensitivity,
                                                    stringInterner,
                                                    fileCollectionSnapshotter,
                                                    normalizedContentHasher
                                            ),
                                            directoryInsensitiveFingerprinters,
                                            insensitiveFingerprinters
                                    )
                            )
                    );
                }).collect(ImmutableSet.toImmutableSet());
    }

    /**
     * These fingerprinters are fully sensitive to both line endings and empty directories
     */
    private static List<? extends FileCollectionFingerprinter> fullySensitiveFingerprinters(
            DirectorySensitivity directorySensitivity,
            StringInterner stringInterner,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            FileSystemLocationSnapshotHasher normalizedContentHasher
    ) {
        return Lists.newArrayList(
//                new AbsolutePathFileCollectionFingerprinter(directorySensitivity, fileCollectionSnapshotter, normalizedContentHasher),
//                new RelativePathFileCollectionFingerprinter(stringInterner, directorySensitivity, fileCollectionSnapshotter, normalizedContentHasher),
//                new NameOnlyFileCollectionFingerprinter(directorySensitivity, fileCollectionSnapshotter, normalizedContentHasher)
        );
    }

    /**
     * These fingerprinters are sensitive to line endings but not empty directories
     */
    private static List<? extends FileCollectionFingerprinter> directoryInsensitiveFingerprinters(
            LineEndingSensitivity lineEndingSensitivity,
            FileSystemLocationSnapshotHasher normalizedContentHasher,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            ResourceSnapshotterCacheService resourceSnapshotterCacheService,
            ResourceFilter resourceFilter,
            ResourceEntryFilter metaInfFilter,
            Map<String, ResourceEntryFilter> propertiesFileFilters,
            StringInterner stringInterner
    ) {
        return Lists.newArrayList(
//                new IgnoredPathFileCollectionFingerprinter(fileCollectionSnapshotter, normalizedContentHasher),
//                new DefaultClasspathFingerprinter(
//                        resourceSnapshotterCacheService,
//                        fileCollectionSnapshotter,
//                        resourceFilter,
//                        metaInfFilter,
//                        propertiesFileFilters,
//                        stringInterner,
//                        lineEndingSensitivity
//                )
        );
    }

    /**
     * These fingerprinters do not care about line ending or directory sensitivity at all
     */
    private static List<? extends FileCollectionFingerprinter> insensitiveFingerprinters(ResourceSnapshotterCacheService resourceSnapshotterCacheService, FileCollectionSnapshotter fileCollectionSnapshotter, StringInterner stringInterner) {
        return Lists.newArrayList(
//                new DefaultCompileClasspathFingerprinter(resourceSnapshotterCacheService, fileCollectionSnapshotter, stringInterner)
        );
    }

    private static Stream<FingerprinterRegistration> registrationsFor(LineEndingSensitivity lineEndingSensitivity, DirectorySensitivity directorySensitivity, Stream<List<? extends FileCollectionFingerprinter>> fingerprinters) {
        return fingerprinters.flatMap(Collection::stream).map(fingerprinter ->
                registration(directorySensitivity, lineEndingSensitivity, fingerprinter)
        );
    }

    private static <T> Stream<T> withAllLineEndingSensitivities(Function<LineEndingSensitivity, Stream<T>> f) {
        return Arrays.stream(LineEndingSensitivity.values()).flatMap(f);
    }

    private static <T> Stream<T> withAllDirectorySensitivities(Function<DirectorySensitivity, Stream<T>> f) {
        return Stream.of(DirectorySensitivity.DEFAULT, DirectorySensitivity.IGNORE_DIRECTORIES).flatMap(f);
    }

    public Set<FingerprinterRegistration> getRegistrants() {
        return registrants;
    }

    private static FileSystemLocationSnapshotHasher normalizedContentHasher(LineEndingSensitivity lineEndingSensitivity, ResourceSnapshotterCacheService resourceSnapshotterCacheService) {
        FileSystemLocationSnapshotHasher resourceHasher = LineEndingNormalizingFileSystemLocationSnapshotHasher
                .wrap(FileSystemLocationSnapshotHasher.DEFAULT, lineEndingSensitivity);
        return cacheIfNormalized(resourceHasher, lineEndingSensitivity, resourceSnapshotterCacheService);
    }

    private static FileSystemLocationSnapshotHasher cacheIfNormalized(FileSystemLocationSnapshotHasher resourceHasher, LineEndingSensitivity lineEndingSensitivity, ResourceSnapshotterCacheService resourceSnapshotterCacheService) {
        switch (lineEndingSensitivity) {
            case DEFAULT:
                return resourceHasher;
            case NORMALIZE_LINE_ENDINGS:
                return new CachingFileSystemLocationSnapshotHasher(resourceHasher, resourceSnapshotterCacheService);
            default:
                throw new IllegalArgumentException();
        }
    }
}
