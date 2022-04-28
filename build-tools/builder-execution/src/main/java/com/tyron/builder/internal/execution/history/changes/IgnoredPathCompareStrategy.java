package com.tyron.builder.internal.execution.history.changes;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.fingerprint.impl.IgnoredPathFingerprintingStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Compares {@link FileCollectionFingerprint}s ignoring the path.
 */
public class IgnoredPathCompareStrategy extends AbstractFingerprintCompareStrategy {
    public static final FingerprintCompareStrategy INSTANCE = new IgnoredPathCompareStrategy();

    private static final Comparator<Map.Entry<HashCode, FilePathWithType>> ENTRY_COMPARATOR = new Comparator<Map.Entry<HashCode, FilePathWithType>>() {
        @Override
        public int compare(Map.Entry<HashCode, FilePathWithType> hashCodeFilePathWithTypeEntry,
                           Map.Entry<HashCode, FilePathWithType> t1) {
            HashCode k = hashCodeFilePathWithTypeEntry.getKey();
            HashCode k1 = t1.getKey();

            return Comparator.comparingInt(HashCode::hashCode).compare(k, k1);
        }
    };

    private IgnoredPathCompareStrategy() {
        super(IgnoredPathCompareStrategy::visitChangesSince);
    }

    /**
     * Determines changes by:
     *
     * <ul>
     *     <li>Determining which content fingerprints are only in the previous or current fingerprint collection.</li>
     *     <li>Those only in the previous fingerprint collection are reported as removed.</li>
     * </ul>
     */
    private static boolean visitChangesSince(
            Map<String, FileSystemLocationFingerprint> previous,
            Map<String, FileSystemLocationFingerprint> current,
            String propertyTitle,
            ChangeVisitor visitor
    ) {
        ListMultimap<HashCode, FilePathWithType> unaccountedForPreviousFiles = MultimapBuilder.hashKeys(previous.size()).linkedListValues().build();
        for (Map.Entry<String, FileSystemLocationFingerprint> entry : previous.entrySet()) {
            String absolutePath = entry.getKey();
            FileSystemLocationFingerprint previousFingerprint = entry.getValue();
            unaccountedForPreviousFiles.put(previousFingerprint.getNormalizedContentHash(), new FilePathWithType(absolutePath, previousFingerprint.getType()));
        }

        for (Map.Entry<String, FileSystemLocationFingerprint> entry : current.entrySet()) {
            String currentAbsolutePath = entry.getKey();
            FileSystemLocationFingerprint currentFingerprint = entry.getValue();
            HashCode normalizedContentHash = currentFingerprint.getNormalizedContentHash();
            List<FilePathWithType> previousFilesForContent = unaccountedForPreviousFiles.get(normalizedContentHash);
            if (previousFilesForContent.isEmpty()) {
                DefaultFileChange added = DefaultFileChange.added(currentAbsolutePath, propertyTitle, currentFingerprint.getType(), IgnoredPathFingerprintingStrategy.IGNORED_PATH);
                if (!visitor.visitChange(added)) {
                    return false;
                }
            } else {
                previousFilesForContent.remove(0);
            }
        }

        List<Map.Entry<HashCode, FilePathWithType>> unaccountedForPreviousEntries = ImmutableList.sortedCopyOf(ENTRY_COMPARATOR, unaccountedForPreviousFiles.entries());
        for (Map.Entry<HashCode, FilePathWithType> unaccountedForPreviousEntry : unaccountedForPreviousEntries) {
            FilePathWithType removedFile = unaccountedForPreviousEntry.getValue();
            DefaultFileChange removed = DefaultFileChange.removed(removedFile.getAbsolutePath(), propertyTitle, removedFile.getFileType(), IgnoredPathFingerprintingStrategy.IGNORED_PATH);
            if (!visitor.visitChange(removed)) {
                return false;
            }
        }
        return true;
    }

}