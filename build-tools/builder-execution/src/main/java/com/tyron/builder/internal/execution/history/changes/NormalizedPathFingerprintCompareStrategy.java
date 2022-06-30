package com.tyron.builder.internal.execution.history.changes;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static java.util.Map.Entry.comparingByKey;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;

/**
 * Compares by normalized path (relative/name only) and file contents. Order does not matter.
 */
public class NormalizedPathFingerprintCompareStrategy extends AbstractFingerprintCompareStrategy {
    public static final FingerprintCompareStrategy INSTANCE = new NormalizedPathFingerprintCompareStrategy();

    private NormalizedPathFingerprintCompareStrategy() {
        super(NormalizedPathFingerprintCompareStrategy::visitChangesSince);
    }

    /**
     * Determines changes by:
     *
     * <ul>
     *     <li>Determining which {@link FileSystemLocationFingerprint}s are only in the previous or current fingerprint collection.</li>
     *     <li>
     *         For those only in the previous fingerprint collection it checks if some entry with the same normalized path is in the current collection.
     *         If it is, file is reported as modified, if not as removed.
     *     </li>
     * </ul>
     */
    private static boolean visitChangesSince(
            Map<String, FileSystemLocationFingerprint> previousFingerprints,
            Map<String, FileSystemLocationFingerprint> currentFingerprints,
            String propertyTitle,
            ChangeVisitor visitor
    ) {
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> unaccountedForPreviousFiles = getUnaccountedForPreviousFingerprints(previousFingerprints, currentFingerprints.entrySet());
        ListMultimap<String, FilePathWithType> addedFilesByNormalizedPath = getAddedFilesByNormalizedPath(currentFingerprints, unaccountedForPreviousFiles, previousFingerprints.entrySet());

        Iterator<Entry<FileSystemLocationFingerprint, FilePathWithType>> iterator = unaccountedForPreviousFiles.entries().stream().sorted(comparingByKey()).iterator();
        while (iterator.hasNext()) {
            Entry<FileSystemLocationFingerprint, FilePathWithType> entry = iterator.next();
            FileSystemLocationFingerprint previousFingerprint = entry.getKey();
            FilePathWithType pathWithType = entry.getValue();

            Change change = getChange(propertyTitle, addedFilesByNormalizedPath, previousFingerprint, pathWithType);
            if (!visitor.visitChange(change)) {
                return false;
            }
        }

        for (Entry<String, FilePathWithType> entry : addedFilesByNormalizedPath.entries()) {
            Change added = added(propertyTitle, entry);
            if (!visitor.visitChange(added)) {
                return false;
            }
        }
        return true;
    }

    private static Change getChange(
            String propertyTitle,
            ListMultimap<String, FilePathWithType> addedFilesByNormalizedPath,
            FileSystemLocationFingerprint previousFingerprint,
            FilePathWithType pathWithType
    ) {
        String normalizedPath = previousFingerprint.getNormalizedPath();
        FileType previousFingerprintType = previousFingerprint.getType();
        String absolutePath = pathWithType.getAbsolutePath();

        List<FilePathWithType> filePathWithTypes = addedFilesByNormalizedPath.get(normalizedPath);
        Optional<FilePathWithType> match = filePathWithTypes.stream().filter(file -> absolutePath.equals(file.getAbsolutePath())).findFirst();
        return match
                .map(filePathWithType -> {
                    filePathWithTypes.remove(filePathWithType);
                    return modified(propertyTitle, previousFingerprintType, normalizedPath, filePathWithType);
                })
                .orElseGet(() -> removed(propertyTitle, normalizedPath, pathWithType));
    }

    private static ListMultimap<FileSystemLocationFingerprint, FilePathWithType> getUnaccountedForPreviousFingerprints(
            Map<String, FileSystemLocationFingerprint> previousFingerprints,
            Set<Entry<String, FileSystemLocationFingerprint>> currentEntries
    ) {
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> results = MultimapBuilder
                .hashKeys(previousFingerprints.size())
                .linkedListValues()
                .build();
        for (Entry<String, FileSystemLocationFingerprint> previousEntry : previousFingerprints.entrySet()) {
            // skip exact matches
            if (currentEntries.contains(previousEntry)) {
                continue;
            }

            String absolutePath = previousEntry.getKey();
            FileSystemLocationFingerprint previousFingerprint = previousEntry.getValue();
            FileType previousFingerprintType = previousFingerprint.getType();

            results.put(previousFingerprint, new FilePathWithType(absolutePath, previousFingerprintType));
        }
        return results;
    }

    private static ListMultimap<String, FilePathWithType> getAddedFilesByNormalizedPath(
            Map<String, FileSystemLocationFingerprint> currentFingerprints,
            ListMultimap<FileSystemLocationFingerprint, FilePathWithType> unaccountedForPreviousFiles,
            Set<Entry<String, FileSystemLocationFingerprint>> previousEntries
    ) {
        ListMultimap<String, FilePathWithType> results = MultimapBuilder
                .linkedHashKeys()
                .arrayListValues(1)
                .build();
        for (Entry<String, FileSystemLocationFingerprint> currentEntry : currentFingerprints.entrySet()) {
            // skip exact matches
            if (previousEntries.contains(currentEntry)) {
                continue;
            }

            String absolutePath = currentEntry.getKey();
            FileSystemLocationFingerprint currentFingerprint = currentEntry.getValue();
            List<FilePathWithType> previousFilesForFingerprint = unaccountedForPreviousFiles.get(currentFingerprint);
            FileType fingerprintType = currentFingerprint.getType();

            if (previousFilesForFingerprint.isEmpty()) {
                results.put(currentFingerprint.getNormalizedPath(), new FilePathWithType(absolutePath, fingerprintType));
            } else {
                previousFilesForFingerprint.remove(0);
            }
        }
        return results;
    }

    private static Change modified(
            String propertyTitle,
            FileType previousFingerprintType,
            String normalizedPath,
            FilePathWithType modifiedFile
    ) {
        String absolutePath = modifiedFile.getAbsolutePath();
        FileType fileType = modifiedFile.getFileType();
        return DefaultFileChange.modified(absolutePath, propertyTitle, previousFingerprintType, fileType, normalizedPath);
    }

    private static Change removed(
            String propertyTitle,
            String normalizedPath,
            FilePathWithType removedFile
    ) {
        String absolutePath = removedFile.getAbsolutePath();
        FileType fileType = removedFile.getFileType();
        return DefaultFileChange.removed(absolutePath, propertyTitle, fileType, normalizedPath);
    }

    private static Change added(
            String propertyTitle,
            Entry<String, FilePathWithType> addedFilesByNormalizedPathEntries
    ) {
        FilePathWithType addedFile = addedFilesByNormalizedPathEntries.getValue();
        String absolutePath = addedFile.getAbsolutePath();
        FileType fileType = addedFile.getFileType();
        String normalizedPath = addedFilesByNormalizedPathEntries.getKey();
        return DefaultFileChange.added(absolutePath, propertyTitle, fileType, normalizedPath);
    }
}